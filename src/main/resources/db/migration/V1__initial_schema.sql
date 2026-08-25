-- ==============================================================================
-- CCE Matcher Service — Initial Database Schema
-- ==============================================================================
-- Flyway Migration: V1
-- Database: PostgreSQL 16
--
-- Owns the runtime plane: protocol instances, steps and their SLA schedule, the inbound-event
-- idempotency log, facilities, and the append-only status history. Also creates `deviation`, whose
-- foreign key points into `step_instance`, and `intelligence_event_log`, which carries no foreign
-- keys — the Compliance Service writes rows in both but owns no DDL.
--
-- Depends on the Protocol Service having migrated first: `protocol_instance.protocol_definition_id`
-- references `protocol_definition`, which that service creates. Deployment order is
-- protocol -> matcher -> compliance.
--
-- The service shares the `ccedb` database with other CCE services and keeps its own
-- Flyway ledger (`spring.flyway.table = flyway_schema_history_matcher`), so its migrations
-- are tracked independently of anything else in the database.
--
-- `id` columns of protocol_instance, step_instance, step_sla_state_transition and deviation carry
-- no database default: they are time-ordered UUIDv7 values generated application-side (Hibernate
-- UuidV7Generator), so insertion order is readable from the key and a range of ids is a range of
-- time. The remaining tables keep gen_random_uuid() defaults.
--
-- REPLICA IDENTITY FULL is set on every CDC-replicated table. Publication membership and
-- CDC-user grants live in data-pipeline/cdc/01-configure-replication.sql, not here.
-- ==============================================================================

-- =============================================
-- 1. protocol_instance
-- =============================================
CREATE TABLE protocol_instance (
    id                      UUID            NOT NULL,
    patient_id              VARCHAR         NOT NULL,
    protocol_definition_id  UUID            NOT NULL,
    enrolled_at             TIMESTAMPTZ     NOT NULL,
    status                  VARCHAR         NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT protocol_instance_pkey PRIMARY KEY (id),
    CONSTRAINT protocol_instance_protocol_definition_id_fkey
        FOREIGN KEY (protocol_definition_id) REFERENCES protocol_definition(id),
    CONSTRAINT protocol_instance_status_check CHECK (status IN ('ACTIVE', 'COMPLETED', 'WITHDRAWN', 'EXPIRED'))
);

-- The active-instance gauge.
CREATE INDEX idx_protocol_instance_status ON protocol_instance (status) WHERE status = 'ACTIVE';
-- The enrolment lookup on every matched event: findByPatientIdAndProtocolDefinitionIdAndStatus. Also
-- serves a lookup by patient_id alone, since that is its leading column — which is why there is no
-- separate index on patient_id: the planner picks this one for that query either way.
CREATE INDEX idx_protocol_instance_enrollment
    ON protocol_instance (patient_id, protocol_definition_id, status);

ALTER TABLE protocol_instance REPLICA IDENTITY FULL;

-- =============================================
-- 2. matcher_event_log
-- =============================================
-- Lean idempotency log for inbound CloudEvents. Created before step_instance so the
-- matched_event_id foreign key can be declared inline.
-- =============================================
CREATE TABLE matcher_event_log (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    cloudevents_id              VARCHAR         NOT NULL,
    source                      VARCHAR         NOT NULL,
    correlation_id              VARCHAR,
    processing_status           VARCHAR         NOT NULL,
    data                        JSONB,
    received_at                 TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT matcher_event_log_pkey PRIMARY KEY (id),
    CONSTRAINT matcher_event_log_cloudevents_id_source_key UNIQUE (cloudevents_id, source),
    CONSTRAINT matcher_event_log_processing_status_check
        CHECK (processing_status IN ('MATCHED', 'ZERO_MATCH', 'DUPLICATE'))
);

ALTER TABLE matcher_event_log REPLICA IDENTITY FULL;

-- =============================================
-- 3. step_instance
-- =============================================
-- A step carries two independent statuses:
--
--   step_status  NOT_STARTED | COMPLETED               — has the expected event arrived?
--   sla_status   NULL | OVERDUE | MISSED | MET         — has the deadline been met?
--
-- step_status uses the FHIR R4 CarePlanActivityStatus codes (not-started / completed); a
-- step_instance is an occurrence of a PlanDefinition action, i.e. a care-plan activity.
--
-- sla_status is NULL until there is something to judge: null is not a status but the absence of a
-- judgement, and it is also the resting state of a step that has no SLA at all (no due date means
-- no step_sla_state_transition rows, so nothing will ever judge it).
--
-- The Compliance Service is its ONLY writer, and it judges from completed_at against the thresholds:
-- completed before the due date is MET, at or after it OVERDUE, at or after the missed date (= due
-- date + tolerance-days) MISSED for a 'must' step. A completed step is settled on the next sweep,
-- since completed_at fixes the answer; one still outstanding is judged when each threshold falls due.
-- Matcher records that the work happened and when, never whether it was timely — so the two services
-- never write this column's value from different evidence. Because the two statuses are independent,
-- a step can be COMPLETED + MISSED (recorded after being written off).
--
-- Both thresholds live on step_sla_state_transition (§4) rather than here, so the service that
-- evaluates due work selects from a table that shrinks as work is processed instead of rescanning
-- every step row.
-- =============================================
CREATE TABLE step_instance (
    id                      UUID            NOT NULL,
    protocol_instance_id    UUID            NOT NULL,
    action_id               VARCHAR         NOT NULL,
    repeat_index            INTEGER         NOT NULL DEFAULT 0,
    step_status             VARCHAR         NOT NULL,
    -- Nullable: null means no threshold has fallen due, so timeliness is not yet judged.
    -- Written only by the Compliance Service. Stays null for a step with no SLA at all.
    sla_status              VARCHAR,
    -- SLA thresholds are not denormalized here; each is a step_sla_state_transition row (see §4).
    completed_at            TIMESTAMPTZ,
    completed_by_source     VARCHAR,
    matched_event_id        UUID,
    required_behavior       VARCHAR,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT step_instance_pkey PRIMARY KEY (id),
    CONSTRAINT step_instance_protocol_instance_id_fkey
        FOREIGN KEY (protocol_instance_id) REFERENCES protocol_instance(id),
    -- Unindexed on purpose: nothing looks a step up by the event that completed it. PostgreSQL does not
    -- index a referencing column for you, and does not need one here — the cost of not having it is a
    -- scan of step_instance per deleted matcher_event_log row, and nothing deletes from that log. Add
    -- one alongside any retention that starts to.
    CONSTRAINT step_instance_matched_event_id_fkey
        FOREIGN KEY (matched_event_id) REFERENCES matcher_event_log(id),
    CONSTRAINT step_instance_step_status_check
        CHECK (step_status IN ('NOT_STARTED', 'COMPLETED')),
    CONSTRAINT step_instance_sla_status_check
        CHECK (sla_status IN ('OVERDUE', 'MISSED', 'MET')),
    CONSTRAINT step_instance_required_behavior_check
        CHECK (required_behavior IN ('must', 'could', 'must-unless-documented'))
);

CREATE INDEX idx_step_instance_protocol ON step_instance (protocol_instance_id);
-- Locating the step a late-arriving event should complete.
CREATE INDEX idx_step_instance_not_started ON step_instance (protocol_instance_id, action_id)
    WHERE step_status = 'NOT_STARTED';
-- The Compliance Service's second claim path: a step that has been completed can have its SLA settled
-- from completed_at at once, without waiting for a threshold to fall due. This is the set it selects —
-- completed but not yet settled — which stays small because a claim empties it. Keeping it a partial
-- index is the point: the alternative is scanning every step's pending schedule on each sweep.
CREATE INDEX idx_step_instance_completed_unjudged ON step_instance (id)
    WHERE step_status = 'COMPLETED'
      AND completed_at IS NOT NULL
      AND (sla_status IS NULL OR sla_status = 'OVERDUE');
-- And deliberately none on sla_status alone. Nothing selects steps by it: due work comes from
-- step_sla_state_transition (§4), and the one query that reads sla_status also filters on step_status
-- and completed_at, so the partial index above serves it and serves it more precisely.

ALTER TABLE step_instance REPLICA IDENTITY FULL;

-- =============================================
-- 4. step_sla_state_transition
-- =============================================
-- Each step's SLA schedule, one row per threshold it can cross, written in the same transaction as
-- the step — so a step never exists without its schedule.
--
-- These thresholds are deliberately not denormalized onto step_instance. Keyed on "is this transition
-- done yet", the table is both the work queue (a partial index that shrinks as work is processed, rather
-- than a scan of every step row) and a durable record of when each deadline fell and when it was
-- applied — rows are retained, never deleted.
--
-- Ownership: the Matcher Service only ever INSERTs here. Deciding which rows are due, writing
-- step_instance.sla_status, recording the OVERDUE / MISSED deviation, and updating is_processed /
-- processed_at / attempts / next_attempt_at all belong to the service that evaluates them. One writer
-- per column, so the evaluator can claim rows without racing us.
--
-- transition_type names the deadline, not a from/to status pair. There are deliberately no from_status
-- and to_status columns: crossing the due date lands a step completed before it on MET and one still
-- outstanding on OVERDUE, so a single destination per type never held. The outcome of applying a row is
-- readable from step_instance.sla_status; what this table records is which deadline fell and when.
--
-- A step usually completes before its row is processed, and that is the point: the evaluator compares
-- step_instance.completed_at against process_by rather than consulting the wall clock. Completed at or
-- after process_by breached that deadline; completed before it did not. Only the due-date row can settle
-- an SLA as MET — beating the missed date merely means the step was not written off, and a step
-- completed between its two thresholds stays the OVERDUE the due-date row made it.
--
-- Because that comparison never consults the clock, a row whose step is already COMPLETED can be applied
-- at once: completed_at is fixed and the thresholds were written at creation, so the deadline arriving
-- would only confirm what is already decided. So a row becomes claimable when next_attempt_at passes OR
-- when its step completes — the second path is why an early completion does not sit at a null sla_status
-- until its due date, and it is what idx_step_instance_completed_unjudged (§3) exists to serve.
-- =============================================
CREATE TABLE step_sla_state_transition (
    id                  UUID            NOT NULL,
    step_instance_id    UUID            NOT NULL,
    transition_type     VARCHAR         NOT NULL,

    -- Absolute, clinical-time-anchored threshold. Immutable: the audit truth for when the deadline fell.
    process_by          TIMESTAMPTZ     NOT NULL,

    -- The "done" mark and its provenance. Owned by the evaluating service.
    is_processed        BOOLEAN         NOT NULL DEFAULT FALSE,
    processed_at        TIMESTAMPTZ,
    processed_by        VARCHAR,

    -- Retry bookkeeping, also owned by the evaluating service. next_attempt_at is the gate it selects
    -- on and starts equal to process_by, so a transient failure defers a retry without rewriting history.
    attempts            INTEGER         NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ     NOT NULL,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT step_sla_state_transition_pkey PRIMARY KEY (id),

    -- Makes creation idempotent: a step has at most one row per transition type. Its leading column
    -- also serves lookups by step, so no separate index on step_instance_id is needed.
    CONSTRAINT step_sla_state_transition_step_type_key UNIQUE (step_instance_id, transition_type),

    CONSTRAINT step_sla_state_transition_step_instance_id_fkey
        FOREIGN KEY (step_instance_id) REFERENCES step_instance(id),

    CONSTRAINT step_sla_state_transition_type_check
        CHECK (transition_type IN ('DUE_DATE_REACHED', 'MISSED_DATE_REACHED'))
);

-- The evaluator's claim path, and the only hot index: partial on the unprocessed set, so the query
-- stays a range scan over just the pending backlog however large the retained history grows.
CREATE INDEX idx_sslt_due
    ON step_sla_state_transition (next_attempt_at)
    WHERE is_processed = FALSE;

ALTER TABLE step_sla_state_transition REPLICA IDENTITY FULL;

-- =============================================
-- 5. deviation
-- =============================================
CREATE TABLE deviation (
    id                      UUID            NOT NULL,
    -- No protocol_instance_id: reachable as step_instance.protocol_instance_id.
    step_instance_id        UUID            NOT NULL,
    deviation_type          VARCHAR         NOT NULL,
    detected_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    intelligence_event_id   UUID,
    metadata                JSONB,
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT deviation_pkey PRIMARY KEY (id),
    CONSTRAINT deviation_step_instance_id_fkey
        FOREIGN KEY (step_instance_id) REFERENCES step_instance(id),
    CONSTRAINT deviation_type_check CHECK (deviation_type IN ('OVERDUE', 'MISSED', 'ORDER_VIOLATION')),
    -- One deviation per (step, type). The backstop against concurrent inserts racing the
    -- application-level existence check.
    CONSTRAINT deviation_step_type_key UNIQUE (step_instance_id, deviation_type)
);

-- No index on deviation_type. The only read is findByStepInstanceIdAndDeviationType, which
-- deviation_step_type_key answers; nothing selects deviations by type alone in Postgres, and the
-- analytics that do run in ClickHouse against the CDC mirror.
ALTER TABLE deviation REPLICA IDENTITY FULL;

-- =============================================
-- 6. intelligence_event_log
-- =============================================
-- One row per fired intelligence action, holding the full published event plus the runtime
-- context it was evaluated against. No foreign keys — this is a self-contained record.
-- =============================================
CREATE TABLE intelligence_event_log (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    event_payload               JSONB           NOT NULL,
    action_definition_id        UUID            NOT NULL,
    protocol_instance_id        UUID            NOT NULL,
    step_instance_id            UUID,
    deviation_id                UUID,
    subject                     VARCHAR         NOT NULL,
    action_type                 VARCHAR         NOT NULL,
    intelligence_destination    VARCHAR         NOT NULL,
    step_status                 VARCHAR         NOT NULL,
    sla_status                  VARCHAR,
    trigger_reason              VARCHAR         NOT NULL,
    step_action_id              VARCHAR,
    evaluation_expression       TEXT,
    evaluation_context          JSONB,
    published                   BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT intelligence_event_log_pkey PRIMARY KEY (id)
);

-- The three filters the Compliance Service's API exposes, and nothing else: there is no index on
-- subject or step_instance_id, because no query has ever selected on either.
CREATE INDEX idx_intelligence_event_log_action_definition ON intelligence_event_log (action_definition_id);
CREATE INDEX idx_intelligence_event_log_protocol_instance ON intelligence_event_log (protocol_instance_id);
CREATE INDEX idx_intelligence_event_log_published ON intelligence_event_log (published) WHERE published = FALSE;

ALTER TABLE intelligence_event_log REPLICA IDENTITY FULL;

-- =============================================
-- 7. facility
-- =============================================
-- Reference table captured from inbound events. facility_name and district_name are populated
-- by programme staff directly in the database; the service never writes to them.
-- =============================================
CREATE TABLE facility (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    facility_id                 VARCHAR         NOT NULL,
    facility_name               VARCHAR,
    district_name               VARCHAR,
    expected_patients_per_day   INTEGER,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT facility_pkey PRIMARY KEY (id),
    CONSTRAINT facility_facility_id_key UNIQUE (facility_id)
);

ALTER TABLE facility REPLICA IDENTITY FULL;

-- =============================================
-- 8. protocol_instance_history / step_instance_history
-- =============================================
-- Append-only status history, written by the application inside the same transaction as the
-- parent mutation. Rows are only ever INSERTed, making these a point-in-time-reconstructible
-- record and a clean CDC source.
--
-- No enum CHECKs on purpose: values are copied from the parent row, which enforces its own.
-- A history CHECK lagging a future parent enum change would roll back the parent write.
-- =============================================
CREATE TABLE protocol_instance_history (
    id                      BIGSERIAL       NOT NULL,
    protocol_instance_id    UUID            NOT NULL,
    status                  VARCHAR         NOT NULL,
    changed_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT protocol_instance_history_pkey PRIMARY KEY (id)
);

CREATE TABLE step_instance_history (
    id                      BIGSERIAL       NOT NULL,
    step_instance_id        UUID            NOT NULL,
    step_status             VARCHAR         NOT NULL,
    -- Nullable: null means no threshold has fallen due, so timeliness is not yet judged.
    -- Written only by the Compliance Service. Stays null for a step with no SLA at all.
    sla_status              VARCHAR,
    changed_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT step_instance_history_pkey PRIMARY KEY (id)
);

-- Unindexed beyond their primary keys, on purpose. Nothing reads them in Postgres: the application
-- only INSERTs, Debezium takes its snapshot as a full read and streams from the WAL, and the
-- reconstruction that reads history back — "every transition for this instance, in order" — runs in
-- ClickHouse against the replicated copy. An index here would be maintained on every status change,
-- the highest write rate in the schema, to serve no query.
