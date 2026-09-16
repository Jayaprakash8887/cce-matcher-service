-- ==============================================================================
-- CCE Matcher Service — remove the SLA schedules of optional steps
-- ==============================================================================
-- Flyway Migration: V4
-- Database: PostgreSQL 16
--
-- Only a mandatory step has a deadline. A deadline is the point at which work the protocol *required*
-- has not been recorded, and nothing is required of an optional step — so it can be neither OVERDUE nor
-- MISSED, and there is no due date it can be said to have beaten either. sla_status is a mandatory
-- step's column, and step_sla_state_transition is a mandatory step's table.
--
-- That is now enforced where rows are written (StepSlaScheduleService writes none for a step whose
-- required_behavior is not 'must'), where protocols are loaded (a tolerance-days on an optional action
-- is refused), and where rows are judged (the Step SLA Service declines any row it finds for one). This
-- migration deals with the rows that predate all three:
--
--   * every optional step created before that rule got its DUE_DATE_REACHED row, and its
--     MISSED_DATE_REACHED row where the protocol declared tolerance-days;
--   * V2's backfill seeds a row for every step with a due_date or missed_date and has no
--     required_behavior filter, so a database upgraded from 1.x arrives here with them.
--
-- V2 cannot be changed — it is applied, and editing it would change its checksum — so the chain heals
-- itself here instead: V2 seeds, V4 removes. Deleting rather than marking processed, because these rows
-- record a schedule that should never have existed. The table's retention rule covers the history of
-- verdicts actually reached; a schedule for work nobody required is not that.
--
-- Deviations already raised against optional steps are NOT touched. They are in `deviation`, they were
-- reported, and rewriting what a past release decided is a separate question from what this one
-- schedules. The same goes for an sla_status an optional step already carries.
--
-- 'must' is the only mandatory value — an absent required_behavior states no requirement, which is the
-- reading RequiredBehavior.isMandatory takes everywhere in the platform. IS DISTINCT FROM keeps the
-- null rows in scope, where = would silently leave them behind.
-- ==============================================================================

DO $$
DECLARE
    removed BIGINT;
BEGIN
    DELETE FROM step_sla_state_transition t
    USING step_instance s
    WHERE t.step_instance_id = s.id
      AND s.required_behavior IS DISTINCT FROM 'must';

    GET DIAGNOSTICS removed = ROW_COUNT;
    RAISE NOTICE 'Removed % SLA transition row(s) belonging to optional steps', removed;
END $$;
