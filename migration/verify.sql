-- ==============================================================================
-- Post-upgrade verification. Run against the target database after the upgrade.
-- Every check should report OK. Anything else means stop and investigate.
-- ==============================================================================

\echo '── 1. Business tables (expect 13; Flyway ledgers are excluded and vary by history)'
SELECT count(*) AS business_tables,
       CASE WHEN count(*) = 13 THEN 'OK' ELSE 'CHECK' END AS status
FROM information_schema.tables
WHERE table_schema = 'public' AND table_name NOT LIKE 'flyway_schema_history%';

\echo '   Ledgers present (the monolith''s own flyway_schema_history normally remains):'
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_name LIKE 'flyway_schema_history%' ORDER BY 1;

\echo '── 2. The 1.x columns must be gone'
SELECT table_name, column_name, 'STILL PRESENT — upgrade did not complete' AS status
FROM information_schema.columns
WHERE table_schema = 'public'
  AND (   (table_name = 'step_instance'          AND column_name IN ('state','completion_status','due_date','overdue_date','missed_date'))
       OR (table_name = 'step_instance_history'  AND column_name IN ('state','completion_status'))
       OR (table_name = 'intelligence_event_log' AND column_name = 'step_state'));
\echo '   (no rows above = OK)'

\echo '── 3. The 2.0.0 columns must be present and NOT NULL'
SELECT table_name||'.'||column_name AS column, is_nullable,
       CASE WHEN is_nullable = 'NO' THEN 'OK' ELSE 'CHECK' END AS status
FROM information_schema.columns
WHERE table_schema = 'public'
  AND (   (table_name = 'step_instance'          AND column_name IN ('step_status','sla_status'))
       OR (table_name = 'step_instance_history'  AND column_name IN ('step_status','sla_status'))
       OR (table_name = 'intelligence_event_log' AND column_name IN ('step_status','sla_status')))
ORDER BY 1;

\echo '── 4. compliance_event_log must have become matcher_event_log'
SELECT
  (SELECT count(*) FROM information_schema.tables WHERE table_name='matcher_event_log')    AS matcher_event_log,
  (SELECT count(*) FROM information_schema.tables WHERE table_name='compliance_event_log') AS compliance_event_log,
  CASE WHEN (SELECT count(*) FROM information_schema.tables WHERE table_name='matcher_event_log')=1
        AND (SELECT count(*) FROM information_schema.tables WHERE table_name='compliance_event_log')=0
       THEN 'OK' ELSE 'CHECK' END AS status;

\echo '── 5. Every step must hold a legal status pair'
SELECT step_status, sla_status, count(*) AS rows
FROM step_instance GROUP BY 1,2 ORDER BY 1,2;

\echo '── 6. No step may be left with an unmapped status'
SELECT count(*) AS invalid_rows,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'CHECK' END AS status
FROM step_instance
WHERE step_status NOT IN ('NOT_STARTED','COMPLETED')
   OR sla_status  NOT IN ('PENDING','OVERDUE','MISSED','MET');

\echo '── 7. SLA schedule: every awaited step with a deadline should have its transition rows'
SELECT t.transition_type, t.is_processed, count(*) AS rows
FROM step_sla_state_transition t GROUP BY 1,2 ORDER BY 1,2;

\echo '── 8. Work the Compliance Service will pick up on its first sweep'
\echo '   These are steps whose deadline has passed and whose transition was not already applied.'
\echo '   Expect a burst of OVERDUE deviations for steps that sat in the old DUE state.'
SELECT count(*) AS pending_transitions_now_due
FROM step_sla_state_transition
WHERE is_processed = FALSE AND next_attempt_at <= now();

\echo '── 9. A transition must never point at a missing step'
SELECT count(*) AS orphaned,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'CHECK' END AS status
FROM step_sla_state_transition t
LEFT JOIN step_instance s ON s.id = t.step_instance_id
WHERE s.id IS NULL;

\echo '── 10. Flyway ledgers'
SELECT 'protocol' AS service, version, description, success FROM flyway_schema_history_protocol
UNION ALL
SELECT 'matcher',            version, description, success FROM flyway_schema_history_matcher
ORDER BY 1, 2;
