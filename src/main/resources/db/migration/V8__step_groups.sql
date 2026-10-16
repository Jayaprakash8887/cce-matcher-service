-- ==============================================================================
-- CCE Matcher Service — step groups
-- ==============================================================================
-- Flyway Migration: V8
-- Database: PostgreSQL 16 (NULLS NOT DISTINCT needs 15 or later)
--
-- A step group is a set of steps that belong together clinically, such as one ANC visit. In the
-- PlanDefinition it is a step-typed action with groupingBehavior logical-group; it is never matched to
-- an event, only its steps are. Each occurrence of a group for an enrollment is one row of
-- step_group_instance, and each step row points to its direct group's occurrence.
--
-- This migration carries the schema for every phase of the step-group work, so the analytics pipeline
-- that copies these tables changes once. Phase 1 opens one-off and nested groups and attaches steps to
-- them; repeats use cycle_index from phases 2 and 3. Group completion and closure (completed_at,
-- closed_at), group deadlines (sla_status, and the group references on step_sla_state_transition and
-- deviation) and intelligence on groups (intelligence_event_log.step_group_instance_id) stay unused until
-- phases 4 and 6.
--
-- DEPLOY ORDER: run this migration before any service on a cce-common-util build that maps
-- step_instance.step_group_instance_id. The Step SLA and Collector services validate their entity
-- mappings against the schema at startup (ddl-auto: validate) and fail without the column.
-- ==============================================================================

-- =============================================
-- 1. step_group_instance
-- =============================================
-- Only facts are stored. Whether an occurrence is "partly done" is worked out from its steps when it
-- is needed. completed_at is stored, not worked out, because the Step SLA Service judges a group
-- deadline from it and parses no PlanDefinitions, so it cannot tell which members exist.
CREATE TABLE step_group_instance (
    id                          UUID            NOT NULL,
    protocol_instance_id        UUID            NOT NULL,
    -- The enclosing group's occurrence; null for a top-level group.
    parent_group_instance_id    UUID,
    -- The group's action.id in the PlanDefinition.
    group_action_id             VARCHAR         NOT NULL,
    -- Which repeat of the group this row is, 0-based, in the order the cycles were opened; 0 for a
    -- group that does not repeat.
    cycle_index                 INTEGER         NOT NULL DEFAULT 0,
    -- When the occurrence's entry steps are due, as its predecessor, its parent or its schedule sets
    -- it — never the time of the event that happened to open it. Null when none can be worked out.
    due_date                    TIMESTAMPTZ,
    -- The group deadline's verdict, same values as step_instance.sla_status. Written only by the Step
    -- SLA Service.
    sla_status                  VARCHAR,
    -- The rest are written by the Matcher Service, all three as clinical event time in UTC.
    -- Whatever opened the occurrence. A calendar-day cycle is opened by the first event of its day, so
    -- its day is not stored: it is opened_at's date in the deployment's time zone. One cycle per day is
    -- held by the enrollment lock every group decision takes; no index can hold it, since the time
    -- zone is a deployment setting.
    opened_at                   TIMESTAMPTZ     NOT NULL,
    -- When the last mandatory member was completed.
    completed_at                TIMESTAMPTZ,
    -- From when nothing more is expected of the occurrence.
    closed_at                   TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT step_group_instance_pkey PRIMARY KEY (id),
    CONSTRAINT step_group_instance_protocol_instance_id_fkey
        FOREIGN KEY (protocol_instance_id) REFERENCES protocol_instance(id),
    CONSTRAINT step_group_instance_parent_group_instance_id_fkey
        FOREIGN KEY (parent_group_instance_id) REFERENCES step_group_instance(id),
    CONSTRAINT step_group_instance_sla_status_check
        CHECK (sla_status IN ('OVERDUE', 'MISSED', 'MET')),
    -- One row per group, parent occurrence and cycle. NULLS NOT DISTINCT is what makes this hold for
    -- top-level groups: without it, two rows with a null parent are not duplicates to PostgreSQL, and
    -- opening a top-level occurrence twice would succeed.
    CONSTRAINT step_group_instance_cycle_key
        UNIQUE NULLS NOT DISTINCT (protocol_instance_id, group_action_id, parent_group_instance_id, cycle_index)
);

CREATE INDEX idx_step_group_instance_parent
    ON step_group_instance (parent_group_instance_id)
    WHERE parent_group_instance_id IS NOT NULL;

-- Lookups by enrollment are served by the leading column of step_group_instance_cycle_key.

-- =============================================
-- 2. step_instance: the step's group occurrence
-- =============================================
-- Null for every step outside a group. Set when the step is created and never changed.
ALTER TABLE step_instance
    ADD COLUMN step_group_instance_id UUID,
    ADD CONSTRAINT step_instance_step_group_instance_id_fkey
        FOREIGN KEY (step_group_instance_id) REFERENCES step_group_instance(id);

-- Sparse: most steps are not in a group.
CREATE INDEX idx_step_instance_group
    ON step_instance (step_group_instance_id)
    WHERE step_group_instance_id IS NOT NULL;

-- =============================================
-- 3. Deadline rows and deviations that point at a group
-- =============================================
-- A group with its own deadline gets the same DUE_DATE_REACHED / MISSED_DATE_REACHED rows and the
-- same OVERDUE / MISSED deviations as a step, pointing at the occurrence instead. Exactly one of the
-- two references is set on every row. The existing (step_instance_id, type) keys ignore rows whose
-- step reference is null, so group rows get their own.
ALTER TABLE step_sla_state_transition
    ALTER COLUMN step_instance_id DROP NOT NULL,
    ADD COLUMN step_group_instance_id UUID,
    ADD CONSTRAINT step_sla_state_transition_step_group_instance_id_fkey
        FOREIGN KEY (step_group_instance_id) REFERENCES step_group_instance(id),
    ADD CONSTRAINT step_sla_state_transition_one_target
        CHECK (num_nonnulls(step_instance_id, step_group_instance_id) = 1);

CREATE UNIQUE INDEX step_sla_state_transition_group_type_key
    ON step_sla_state_transition (step_group_instance_id, transition_type)
    WHERE step_group_instance_id IS NOT NULL;

ALTER TABLE deviation
    ALTER COLUMN step_instance_id DROP NOT NULL,
    ADD COLUMN step_group_instance_id UUID,
    ADD CONSTRAINT deviation_step_group_instance_id_fkey
        FOREIGN KEY (step_group_instance_id) REFERENCES step_group_instance(id),
    ADD CONSTRAINT deviation_one_target
        CHECK (num_nonnulls(step_instance_id, step_group_instance_id) = 1);

CREATE UNIQUE INDEX deviation_group_type_key
    ON deviation (step_group_instance_id, deviation_type)
    WHERE step_group_instance_id IS NOT NULL;

-- =============================================
-- 4. Intelligence events about a group
-- =============================================
-- Like step_instance_id here, a plain id with no foreign key. A group has no step_status, so the
-- column the event copies it into becomes nullable.
ALTER TABLE intelligence_event_log
    ADD COLUMN step_group_instance_id UUID,
    ALTER COLUMN step_status DROP NOT NULL;
