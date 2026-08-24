-- ==============================================================================
-- CCE Matcher Service — Upgrade an existing (pre-split) ccedb to the V1 shape
-- ==============================================================================
-- Flyway Migration: V2
-- Database: PostgreSQL 16
--
-- V1 is a greenfield migration: it creates the 2.0.0 schema from nothing. A ccedb carried over from
-- the monolithic compliance service already holds these tables in their 1.x shape, so V1 is baselined
-- there (spring.flyway.baseline-version = 1) and this migration performs the transformation instead.
--
-- Every block is guarded on the presence of the 1.x shape, so on a greenfield database — where V1 has
-- just produced the target shape — this migration is a no-op. That is what lets one chain serve both
-- paths.
--
-- PRECONDITION: the source database must be at monolith V9 or later. V9 reversed the direction of
-- PlanDefinition.action.relatedAction inside protocol_definition.definition, and nothing here repeats
-- that work — the JSON is data this service does not own.
--
-- What changes, and why:
--   * step_instance.state splits into step_status (did the work happen?) and sla_status (was it on
--     time?). The two were conflated, which made "completed, but late" unrepresentable.
--   * completion_status is dropped: EARLY/ON_TIME/LATE is derivable from the pair.
--   * due_date / overdue_date / missed_date leave step_instance. Deadlines now live as rows in
--     step_sla_state_transition, one per threshold, which is also the handoff to the Compliance
--     Service.
--   * compliance_event_log becomes matcher_event_log, following the service that owns it.
--
-- SLA status is derived from timestamps rather than from completion_status, because that is what the
-- runtime does when it settles a completed step: the clinical occurrence time is better evidence than
-- a status column written by an earlier version.
-- ==============================================================================

-- ── 1. compliance_event_log → matcher_event_log ──────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'compliance_event_log')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'matcher_event_log')
    THEN
        ALTER TABLE compliance_event_log RENAME TO matcher_event_log;
        -- Renaming a table leaves its constraint and index names behind; bring them along so the
        -- schema is indistinguishable from one V1 created.
        ALTER INDEX  IF EXISTS compliance_event_log_pkey
            RENAME TO matcher_event_log_pkey;
        ALTER INDEX  IF EXISTS compliance_event_log_cloudevents_id_source_key
            RENAME TO matcher_event_log_cloudevents_id_source_key;
        RAISE NOTICE 'Renamed compliance_event_log to matcher_event_log';
    END IF;
END $$;

-- ── 2. step_instance: split state into step_status + sla_status ──────────────
DO $$
DECLARE
    undecidable BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'step_instance'
                     AND column_name = 'state')
    THEN
        RAISE NOTICE 'step_instance already split; skipping';
        RETURN;
    END IF;

    -- A completed step with no completed_at and no completion_status cannot have its SLA decided
    -- either way. Fail rather than invent one: a wrong MET hides a real breach.
    SELECT count(*) INTO undecidable
    FROM step_instance
    WHERE state = 'COMPLETED' AND completed_at IS NULL AND completion_status IS NULL;

    IF undecidable > 0 THEN
        RAISE EXCEPTION 'Cannot derive sla_status for % completed step_instance row(s) with neither '
                        'completed_at nor completion_status. Resolve these rows, then re-run.',
                        undecidable;
    END IF;

    ALTER TABLE step_instance ADD COLUMN step_status VARCHAR;
    ALTER TABLE step_instance ADD COLUMN sla_status  VARCHAR;

    -- step_status: only a completed step has had its expected event. SKIPPED was an optional step
    -- whose event never arrived, so it is NOT_STARTED here — the fact that it was excused is carried
    -- by sla_status = MET, not by pretending the work happened.
    UPDATE step_instance
    SET step_status = CASE WHEN state = 'COMPLETED' THEN 'COMPLETED' ELSE 'NOT_STARTED' END;

    UPDATE step_instance
    SET sla_status = CASE
        -- DUE and PENDING always meant the same thing, and both are now the absence of a
        -- judgement: NULL. The Compliance Service writes a status when a threshold falls due.
        WHEN state IN ('PENDING', 'DUE')  THEN NULL
        WHEN state = 'OVERDUE'            THEN 'OVERDUE'
        WHEN state = 'MISSED'             THEN 'MISSED'
        -- An optional step allowed to lapse breached nothing.
        WHEN state = 'SKIPPED'            THEN 'MET'
        WHEN state = 'COMPLETED' THEN
            CASE
                WHEN completed_at IS NULL THEN
                    -- No timestamp to judge by; fall back to the recorded verdict.
                    CASE WHEN completion_status = 'LATE' THEN 'OVERDUE' ELSE 'MET' END
                WHEN due_date IS NULL                        THEN 'MET'
                WHEN completed_at <  due_date                THEN 'MET'
                WHEN missed_date IS NOT NULL
                     AND completed_at >= missed_date          THEN 'MISSED'
                ELSE 'OVERDUE'
            END
    END;

    ALTER TABLE step_instance ALTER COLUMN step_status SET NOT NULL;
    -- sla_status stays nullable: null means no threshold has fallen due yet.
END $$;

-- ── 3. step_sla_state_transition: create, then backfill from the old columns ──
CREATE TABLE IF NOT EXISTS step_sla_state_transition (
    id                  UUID            NOT NULL,
    step_instance_id    UUID            NOT NULL,
    transition_type     VARCHAR         NOT NULL,
    process_by          TIMESTAMPTZ     NOT NULL,
    is_processed        BOOLEAN         NOT NULL DEFAULT FALSE,
    processed_at        TIMESTAMPTZ,
    processed_by        VARCHAR,
    attempts            INTEGER         NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT step_sla_state_transition_pkey PRIMARY KEY (id),
    CONSTRAINT step_sla_state_transition_step_type_key UNIQUE (step_instance_id, transition_type),
    CONSTRAINT step_sla_state_transition_step_instance_id_fkey
        FOREIGN KEY (step_instance_id) REFERENCES step_instance(id),
    CONSTRAINT step_sla_state_transition_type_check
        CHECK (transition_type IN ('DUE_DATE_REACHED', 'MISSED_DATE_REACHED'))
);

CREATE INDEX IF NOT EXISTS idx_sslt_due
    ON step_sla_state_transition (next_attempt_at) WHERE is_processed = FALSE;

ALTER TABLE step_sla_state_transition REPLICA IDENTITY FULL;

DO $$
BEGIN
    -- Only meaningful while the old threshold columns are still present to read.
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'step_instance'
                     AND column_name = 'due_date')
    THEN
        RAISE NOTICE 'step_instance has no due_date; nothing to backfill';
        RETURN;
    END IF;

    -- One row per threshold that exists. A transition whose effect the old system already applied is
    -- inserted already-processed: leaving it pending would make the Compliance Service re-apply it and
    -- record a deviation the monolith's scheduler had recorded once already.
    --
    -- process_by is the deadline itself, so the row remains the audit truth of when it fell due, and
    -- next_attempt_at starts equal to it, exactly as the runtime writes them.
    INSERT INTO step_sla_state_transition
        (id, step_instance_id, transition_type,
         process_by, is_processed, processed_at, processed_by, attempts, next_attempt_at, created_at)
    SELECT gen_random_uuid(), s.id, 'DUE_DATE_REACHED',
           s.due_date,
           s.sla_status IS NOT NULL,
           CASE WHEN s.sla_status IS NOT NULL THEN s.due_date END,
           CASE WHEN s.sla_status IS NOT NULL THEN 'migration:V2' END,
           0, s.due_date, now()
    FROM step_instance s
    WHERE s.due_date IS NOT NULL
    ON CONFLICT (step_instance_id, transition_type) DO NOTHING;

    INSERT INTO step_sla_state_transition
        (id, step_instance_id, transition_type,
         process_by, is_processed, processed_at, processed_by, attempts, next_attempt_at, created_at)
    SELECT gen_random_uuid(), s.id, 'MISSED_DATE_REACHED',
           s.missed_date,
           -- Still actionable only while the step is awaited and has not yet been written off.
           NOT (s.step_status = 'NOT_STARTED'
                AND (s.sla_status IS NULL OR s.sla_status = 'OVERDUE')),
           CASE WHEN NOT (s.step_status = 'NOT_STARTED'
                AND (s.sla_status IS NULL OR s.sla_status = 'OVERDUE')) THEN s.missed_date END,
           CASE WHEN NOT (s.step_status = 'NOT_STARTED'
                AND (s.sla_status IS NULL OR s.sla_status = 'OVERDUE')) THEN 'migration:V2' END,
           0, s.missed_date, now()
    FROM step_instance s
    WHERE s.missed_date IS NOT NULL
    ON CONFLICT (step_instance_id, transition_type) DO NOTHING;

    RAISE NOTICE 'Backfilled % SLA transition row(s)',
        (SELECT count(*) FROM step_sla_state_transition);
END $$;

-- ── 4. step_instance: retire the old columns and indexes ─────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'step_instance'
                 AND column_name = 'state')
    THEN
        DROP INDEX IF EXISTS idx_step_instance_state;
        DROP INDEX IF EXISTS idx_step_instance_due_date;
        -- The scheduler service's partial indexes, if it ever ran against this database. It is
        -- retired by this release: its work is now step_sla_state_transition plus the Compliance
        -- Service's sweep.
        DROP INDEX IF EXISTS idx_step_instance_due_overdue;
        DROP INDEX IF EXISTS idx_step_instance_overdue_missed;
        DROP INDEX IF EXISTS idx_step_instance_due_missed;

        ALTER TABLE step_instance DROP CONSTRAINT IF EXISTS step_instance_state_check;
        ALTER TABLE step_instance DROP CONSTRAINT IF EXISTS step_instance_completion_status_check;

        ALTER TABLE step_instance DROP COLUMN state;
        ALTER TABLE step_instance DROP COLUMN completion_status;
        ALTER TABLE step_instance DROP COLUMN due_date;
        ALTER TABLE step_instance DROP COLUMN overdue_date;
        ALTER TABLE step_instance DROP COLUMN missed_date;

        ALTER TABLE step_instance ADD CONSTRAINT step_instance_step_status_check
            CHECK (step_status IN ('NOT_STARTED', 'COMPLETED'));
        ALTER TABLE step_instance ADD CONSTRAINT step_instance_sla_status_check
            CHECK (sla_status IN ('OVERDUE', 'MISSED', 'MET'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_step_instance_not_started
    ON step_instance (protocol_instance_id, action_id) WHERE step_status = 'NOT_STARTED';
CREATE INDEX IF NOT EXISTS idx_step_instance_sla_status
    ON step_instance (sla_status) WHERE sla_status IS NULL OR sla_status = 'OVERDUE';

-- ── 5. step_instance_history: same split, no timestamps to judge by ──────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'step_instance_history'
                     AND column_name = 'state')
    THEN
        RAISE NOTICE 'step_instance_history already split; skipping';
        RETURN;
    END IF;

    ALTER TABLE step_instance_history ADD COLUMN step_status VARCHAR;
    ALTER TABLE step_instance_history ADD COLUMN sla_status  VARCHAR;

    -- Point-in-time rows carry no deadline, so a completed row's SLA can only come from the verdict
    -- recorded alongside it. Values are enum names here, matching what the history writer emits.
    UPDATE step_instance_history
    SET step_status = CASE WHEN state = 'COMPLETED' THEN 'COMPLETED' ELSE 'NOT_STARTED' END,
        sla_status  = CASE
            WHEN state IN ('PENDING', 'DUE') THEN NULL
            WHEN state = 'OVERDUE'           THEN 'OVERDUE'
            WHEN state = 'MISSED'            THEN 'MISSED'
            WHEN state = 'SKIPPED'           THEN 'MET'
            WHEN state = 'COMPLETED' THEN
                CASE WHEN completion_status = 'LATE' THEN 'OVERDUE' ELSE 'MET' END
        END;

    ALTER TABLE step_instance_history ALTER COLUMN step_status SET NOT NULL;
    -- sla_status stays nullable here too, for the rows that predate any judgement.
    ALTER TABLE step_instance_history DROP COLUMN state;
    ALTER TABLE step_instance_history DROP COLUMN completion_status;
END $$;

CREATE INDEX IF NOT EXISTS idx_step_instance_history_step
    ON step_instance_history (step_instance_id, changed_at);

-- ── 6. intelligence_event_log: step_state splits, in FHIR-code form ──────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'intelligence_event_log'
                     AND column_name = 'step_state')
    THEN
        RAISE NOTICE 'intelligence_event_log already split; skipping';
        RETURN;
    END IF;

    ALTER TABLE intelligence_event_log ADD COLUMN step_status VARCHAR;
    ALTER TABLE intelligence_event_log ADD COLUMN sla_status  VARCHAR;

    -- Lowercase here, unlike step_instance: this table records the codes that went on the wire, and
    -- step_status carries the FHIR CarePlanActivityStatus code ('not-started', 'completed').
    UPDATE intelligence_event_log
    SET step_status = CASE WHEN step_state = 'completed' THEN 'completed' ELSE 'not-started' END,
        sla_status  = CASE
            WHEN step_state IN ('pending', 'due') THEN NULL
            WHEN step_state = 'overdue'           THEN 'overdue'
            WHEN step_state = 'missed'            THEN 'missed'
            WHEN step_state = 'skipped'           THEN 'met'
            -- A completed snapshot recorded no timing; 'met' is the only non-breaching reading, and
            -- the deviation rows remain the record of what actually breached.
            WHEN step_state = 'completed'         THEN 'met'
            ELSE NULL
        END;

    ALTER TABLE intelligence_event_log ALTER COLUMN step_status SET NOT NULL;
    -- sla_status stays nullable: a snapshot taken before any threshold fell has none.
    ALTER TABLE intelligence_event_log DROP COLUMN step_state;
END $$;

-- ── 7. Indexes V1 adds that the 1.x schema lacked ────────────────────────────
CREATE INDEX IF NOT EXISTS idx_protocol_instance_enrollment
    ON protocol_instance (patient_id, protocol_definition_id, status);
CREATE INDEX IF NOT EXISTS idx_protocol_instance_history_instance
    ON protocol_instance_history (protocol_instance_id, changed_at);

-- ── 8. Columns 2.0.0 removes ─────────────────────────────────────────────────
-- Three columns the 1.x schema carried that 2.0.0 does not. Each is derivable from what remains, so
-- nothing is lost by dropping them — but each is also read by downstream services, which is why this
-- runs last: the drop is the final step of the cutover, after the tables above are in their new shape.
DO $$
BEGIN
    -- protocol_instance.protocol_canonical == protocol_definition.url || '|' || version. Since
    -- (url, version) is unique and a new version lands as a new row, that join is stable rather than
    -- a point-in-time snapshot, so the stored copy was pure denormalization.
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'protocol_instance'
                 AND column_name = 'protocol_canonical')
    THEN
        ALTER TABLE protocol_instance DROP COLUMN protocol_canonical;
        RAISE NOTICE 'Dropped protocol_instance.protocol_canonical';
    END IF;

    -- deviation.protocol_instance_id is reachable as step_instance.protocol_instance_id, and
    -- step_instance_id is NOT NULL, so the second column could only ever agree or be wrong.
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'deviation'
                 AND column_name = 'protocol_instance_id')
    THEN
        DROP INDEX IF EXISTS idx_deviation_protocol;
        ALTER TABLE deviation DROP COLUMN protocol_instance_id;
        RAISE NOTICE 'Dropped deviation.protocol_instance_id';
    END IF;

    -- Renamed, not dropped: "completed_by" implied the event completed the step, but the column is set
    -- whenever an event matches, and matching is what it records.
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'step_instance'
                 AND column_name = 'completed_by_event_id')
    THEN
        ALTER TABLE step_instance RENAME COLUMN completed_by_event_id TO matched_event_id;
        RAISE NOTICE 'Renamed step_instance.completed_by_event_id to matched_event_id';
    END IF;
END $$;

-- ── 9. audit_log ─────────────────────────────────────────────────────────────
-- Dropped in 2.0.0. The append-only history tables carry state transitions, and actor attribution is
-- planned to move onto the domain tables themselves rather than a parallel log. Kept as a separate
-- statement from §8 so an operator who wants to retain the 1.x audit trail can comment out just this.
DROP TABLE IF EXISTS audit_log;
