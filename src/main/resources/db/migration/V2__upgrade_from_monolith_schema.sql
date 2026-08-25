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
--   * step_instance.completed_by_event_id becomes matched_event_id, and gains the foreign key and
--     partial index V1 declares on it — 1.x had neither.
--   * protocol_instance.protocol_canonical and deviation.protocol_instance_id are dropped: both are
--     reachable by foreign key from what remains.
--   * audit_log is dropped. Those last three run last (§8, §9), after the tables above are in shape.
--
-- Beyond those, the migration also reconciles what a rename cannot carry and what 1.x never declared:
-- constraint and index names, the matcher_event_log processing_status CHECK, and REPLICA IDENTITY FULL
-- on the CDC-replicated tables. An upgraded database and a greenfield one have to be the same schema.
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

-- The processing_status CHECK, which the rename cannot carry either: a 1.x database holds it under the
-- old table's name, or never declared it at all and left the values to the application. Both 1.x and
-- 2.0.0 allow exactly MATCHED / ZERO_MATCH / DUPLICATE, so the constraint only ever writes down what
-- the data already obeys.
DO $$
DECLARE
    stray TEXT;
BEGIN
    IF to_regclass('public.matcher_event_log') IS NULL THEN
        RAISE NOTICE 'No matcher_event_log; nothing to reconcile';
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'matcher_event_log'::regclass
                 AND conname = 'compliance_event_log_processing_status_check')
    THEN
        ALTER TABLE matcher_event_log
            RENAME CONSTRAINT compliance_event_log_processing_status_check
                           TO matcher_event_log_processing_status_check;
        RAISE NOTICE 'Renamed compliance_event_log_processing_status_check';

    ELSIF NOT EXISTS (SELECT 1 FROM pg_constraint
                      WHERE conrelid = 'matcher_event_log'::regclass
                        AND conname = 'matcher_event_log_processing_status_check')
    THEN
        -- Name the offending values rather than letting the ADD fail on them: an unexpected status is
        -- data to resolve, not a schema difference to work around.
        SELECT string_agg(DISTINCT processing_status, ', ') INTO stray
        FROM matcher_event_log
        WHERE processing_status NOT IN ('MATCHED', 'ZERO_MATCH', 'DUPLICATE');

        IF stray IS NOT NULL THEN
            RAISE EXCEPTION 'Cannot add matcher_event_log_processing_status_check: unexpected '
                            'processing_status value(s) present (%). Resolve them, then re-run.', stray;
        END IF;

        ALTER TABLE matcher_event_log ADD CONSTRAINT matcher_event_log_processing_status_check
            CHECK (processing_status IN ('MATCHED', 'ZERO_MATCH', 'DUPLICATE'));
        RAISE NOTICE 'Added matcher_event_log_processing_status_check';
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
-- Not created, and dropped where an earlier build of this release left one behind: nothing selects
-- steps by sla_status alone. Unguarded on purpose, so it also cleans a database built by that earlier
-- V1 rather than only a 1.x one.
DROP INDEX IF EXISTS idx_step_instance_sla_status;

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

-- No index here, and the 1.x one dropped: nothing reads this table in Postgres (V1 §8).
DROP INDEX IF EXISTS idx_step_instance_history_step;

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

-- ── 7. Indexes and replica identity: what V1 has that 1.x lacks, and vice versa ──
CREATE INDEX IF NOT EXISTS idx_protocol_instance_enrollment
    ON protocol_instance (patient_id, protocol_definition_id, status);

-- Five more indexes 1.x carried that V1 does not create, each serving no query in 2.0.0 (a sixth, on
-- step_instance_history, is dropped with that table in §5). Dropped unguarded rather than inside the 1.x
-- branch, so this also cleans a database built by an earlier build of this release, which is the only
-- other place they exist. The reasoning for each sits in V1 beside the table it belonged to: patient_id
-- is the leading column of the enrolment index; deviation_type is answered by deviation_step_type_key;
-- nothing selects intelligence events by subject or by step; and the history tables are read in
-- ClickHouse, never here.
DROP INDEX IF EXISTS idx_protocol_instance_patient;
DROP INDEX IF EXISTS idx_deviation_type;
DROP INDEX IF EXISTS idx_intelligence_event_log_subject;
DROP INDEX IF EXISTS idx_intelligence_event_log_step_instance;
DROP INDEX IF EXISTS idx_protocol_instance_history_instance;

-- V1 sets REPLICA IDENTITY FULL on every CDC-replicated table it creates, so an upgraded database has
-- to carry it too or Debezium sends incomplete before-images for UPDATE and DELETE. The data-pipeline's
-- cdc/01-configure-replication.sql sets the same thing, but it is a separate script an operator may not
-- have run against this database yet, and the setting is idempotent. step_sla_state_transition gets its
-- own in §3; the history tables are append-only and stay on the default PK identity, as in V1.
ALTER TABLE protocol_instance      REPLICA IDENTITY FULL;
ALTER TABLE matcher_event_log      REPLICA IDENTITY FULL;
ALTER TABLE step_instance          REPLICA IDENTITY FULL;
ALTER TABLE deviation              REPLICA IDENTITY FULL;
ALTER TABLE intelligence_event_log REPLICA IDENTITY FULL;
ALTER TABLE facility               REPLICA IDENTITY FULL;

-- ── 8. Columns 2.0.0 removes, and the one it renames ─────────────────────────
-- Two columns the 1.x schema carried that 2.0.0 does not, plus the completed_by_event_id rename. Each
-- dropped column is derivable from what remains, so nothing is lost — but each is also read by
-- downstream services, which is why this runs last: it is the final step of the cutover, after the
-- tables above are in their new shape.
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

-- The column's foreign key and index, which the rename above cannot bring with it: renaming a column
-- leaves constraint and index names behind (§1), and 1.x declared neither in the first place — the link
-- to the event log was application-only. V1 declares both, so an upgraded database has to end up with
-- them under V1's names or the two schemas are not the same schema.
DO $$
DECLARE
    fk_name TEXT;
    orphans BIGINT;
BEGIN
    SELECT c.conname INTO fk_name
    FROM pg_constraint c
    WHERE c.conrelid = 'step_instance'::regclass
      AND c.contype = 'f'
      AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a
                            WHERE a.attrelid = 'step_instance'::regclass
                              AND a.attname = 'matched_event_id')]::smallint[];

    IF fk_name IS NULL THEN
        -- A row pointing at an event that is no longer in the log would fail the ADD, taking the whole
        -- migration with it. Say which rows and stop, rather than leaving the operator to read a
        -- constraint-violation message: the fix is a data decision, not a schema one.
        SELECT count(*) INTO orphans
        FROM step_instance s
        WHERE s.matched_event_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM matcher_event_log e WHERE e.id = s.matched_event_id);

        IF orphans > 0 THEN
            RAISE EXCEPTION 'Cannot add step_instance_matched_event_id_fkey: % step_instance row(s) '
                            'point at a matched_event_id with no matcher_event_log row. Null those '
                            'references or restore the events, then re-run.', orphans;
        END IF;

        ALTER TABLE step_instance ADD CONSTRAINT step_instance_matched_event_id_fkey
            FOREIGN KEY (matched_event_id) REFERENCES matcher_event_log(id);
        RAISE NOTICE 'Added step_instance_matched_event_id_fkey';

    ELSIF fk_name <> 'step_instance_matched_event_id_fkey' THEN
        EXECUTE format('ALTER TABLE step_instance RENAME CONSTRAINT %I TO '
                       'step_instance_matched_event_id_fkey', fk_name);
        RAISE NOTICE 'Renamed % to step_instance_matched_event_id_fkey', fk_name;
    END IF;
END $$;

-- The column carries no index in 2.0.0 (V1 §3), so every name it has ever had is dropped: the two 1.x
-- ones, and the one an earlier build of this release created.
DROP INDEX IF EXISTS idx_step_instance_completed_by_event_id;
DROP INDEX IF EXISTS idx_step_instance_completed_event;
DROP INDEX IF EXISTS idx_step_instance_matched_event;

-- The Compliance Service claims a completed step's transitions from this index rather than waiting for
-- their deadlines (V1 §3). 1.x had no equivalent — it had no such claim path — so it is created here.
CREATE INDEX IF NOT EXISTS idx_step_instance_completed_unjudged
    ON step_instance (id)
    WHERE step_status = 'COMPLETED'
      AND completed_at IS NOT NULL
      AND (sla_status IS NULL OR sla_status = 'OVERDUE');

-- ── 9. audit_log ─────────────────────────────────────────────────────────────
-- Dropped in 2.0.0. The append-only history tables carry state transitions, and actor attribution is
-- planned to move onto the domain tables themselves rather than a parallel log. Kept as a separate
-- statement from §8 so an operator who wants to retain the 1.x audit trail can comment out just this.
DROP TABLE IF EXISTS audit_log;
