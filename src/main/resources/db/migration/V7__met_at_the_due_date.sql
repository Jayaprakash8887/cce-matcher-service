-- ==============================================================================
-- CCE Matcher Service — work recorded at the due-date instant is on time
-- ==============================================================================
-- Flyway Migration: V7
-- Database: PostgreSQL 16
--
-- The on-time boundary was exclusive: Matcher scheduled MET only when completed_at was strictly
-- before due_date, and the Step SLA Service judged completed_at >= process_by a breach. So work
-- recorded at the due-date instant itself was late. That is not an edge case: a successor with no
-- timing offset is due at its prerequisite's completed_at, and a backfilled prerequisite at the
-- clinical time of the completion that revealed it, so when one encounter records both steps the two
-- timestamps are identical. The boundary is now inclusive in both services.
--
-- This migration schedules the MET rows the old rule never wrote: mandatory steps completed exactly
-- at their due date and still unjudged. Without it they would keep a null sla_status for good — under
-- the new rule their DUE_DATE_REACHED row is no longer a breach, and nothing else would reach MET.
-- Same shape as V3, which seeded the strictly-earlier ones: process_by and next_attempt_at are the
-- completed_at, so the rows are due at once.
--
-- Steps the old rule already judged — sla_status OVERDUE with completed_at = due_date, and the
-- OVERDUE deviation recorded alongside — are NOT rewritten. They were reported, and correcting past
-- verdicts is a separate decision from what this release schedules (as V4 reasons for optional steps).
--
-- DEPLOY ORDER: the Step SLA Service's matching change must be running before this migration's rows
-- are fetched. An older Step SLA Service applies a MET row with the strict check, finds the step
-- "no longer reads as on time", and marks the row processed with no verdict. Deploy Step SLA first —
-- it has no schema change in this release, so it can safely go ahead of Matcher — or keep it stopped
-- until Matcher has migrated.
-- ==============================================================================

INSERT INTO step_sla_state_transition
    (id, step_instance_id, transition_type, process_by, is_processed, attempts, next_attempt_at, created_at)
SELECT gen_random_uuid(), s.id, 'MET_CONDITION_REACHED',
       s.completed_at, FALSE, 0, s.completed_at, now()
FROM step_instance s
WHERE s.step_status = 'COMPLETED'
  AND s.sla_status IS NULL
  AND s.completed_at IS NOT NULL
  AND s.due_date IS NOT NULL
  AND s.completed_at = s.due_date
  AND s.required_behavior = 'must'
ON CONFLICT (step_instance_id, transition_type) DO NOTHING;
