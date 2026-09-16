-- ==============================================================================
-- CCE Matcher Service — MET becomes a scheduled transition
-- ==============================================================================
-- Flyway Migration: V3
-- Database: PostgreSQL 16
--
-- Until now MET was the one SLA verdict with no row behind it: the Step SLA Service swept
-- step_instance for completed work that beat its due_date. That sweep is a full scan of the largest
-- table in the schema, which is exactly what step_sla_state_transition exists to avoid — and it made
-- MET the one verdict reached by a different code path from OVERDUE and MISSED.
--
-- So Matcher now writes a MET_CONDITION_REACHED row when a completing event lands before the step's
-- due date, and the Step SLA Service applies it like any other row. This migration admits the new
-- transition_type, seeds the rows for work already completed on time and not yet judged, and drops
-- the index the retired sweep read.
-- ==============================================================================

-- ── 1. Admit the new transition type ─────────────────────────────────────────
ALTER TABLE step_sla_state_transition
    DROP CONSTRAINT IF EXISTS step_sla_state_transition_type_check;

ALTER TABLE step_sla_state_transition
    ADD CONSTRAINT step_sla_state_transition_type_check
        CHECK (transition_type IN ('DUE_DATE_REACHED', 'MISSED_DATE_REACHED', 'MET_CONDITION_REACHED'));

-- ── 2. Seed the rows the retired sweep would have taken ──────────────────────
-- Every step the sweep still had to settle: completed, on time, and not yet judged. Without this they
-- would keep their null sla_status for good, since nothing else looks for them any more.
--
-- process_by is the completed_at that satisfied the condition, and next_attempt_at starts equal to it,
-- exactly as the runtime writes them — so the rows are due at once and drain on the next few cycles.
--
-- Mandatory steps only, matching the runtime: an optional step has no deadline, so no due date it can
-- be said to have beaten. An optional step that is already MET keeps that status; this only declines
-- to reach the verdict afresh.
INSERT INTO step_sla_state_transition
    (id, step_instance_id, transition_type, process_by, is_processed, attempts, next_attempt_at, created_at)
SELECT gen_random_uuid(), s.id, 'MET_CONDITION_REACHED',
       s.completed_at, FALSE, 0, s.completed_at, now()
FROM step_instance s
WHERE s.step_status = 'COMPLETED'
  AND s.sla_status IS NULL
  AND s.completed_at IS NOT NULL
  AND s.due_date IS NOT NULL
  AND s.completed_at < s.due_date
  AND s.required_behavior = 'must'
ON CONFLICT (step_instance_id, transition_type) DO NOTHING;

-- ── 3. Drop the retired sweep's index ────────────────────────────────────────
-- V2 created this for the on-time sweep's predicate. The sweep is gone, so the index has no reader,
-- and a partial index over a moving set is not free to maintain.
DROP INDEX IF EXISTS idx_step_instance_completed_unjudged;
