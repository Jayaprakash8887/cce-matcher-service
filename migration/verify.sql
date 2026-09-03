-- ==============================================================================
-- Post-upgrade verification. Run against the target database after the upgrade.
-- Every check should report OK. Anything else means stop and investigate.
-- ==============================================================================

\echo '── 1. The 12 tables this upgrade accounts for must all be present'
\echo '   12 = the Protocol Service''s 3 plus the Matcher Service''s 9. A total row count is no use'
\echo '   here: ccedb is shared, so the Intelligence Service''s tables, a retired Scheduler''s leftovers'
\echo '   (scheduler_lease, scheduler_scan_cursor) and the monolith''s ledger all sit in the same schema.'
SELECT expected AS table,
       CASE WHEN t.table_name IS NULL THEN 'MISSING' ELSE 'OK' END AS status
FROM unnest(ARRAY['protocol_definition','action_definition','trigger_index',
                  'protocol_instance','matcher_event_log','step_instance','step_sla_state_transition',
                  'deviation','intelligence_event_log','facility',
                  'protocol_instance_history','step_instance_history']) AS expected
LEFT JOIN information_schema.tables t
       ON t.table_schema = 'public' AND t.table_name = expected
ORDER BY 1;

\echo '   Everything else in the schema, for the operator''s eye — nothing here is a failure, but'
\echo '   audit_log must not appear (§9) and the scheduler tables are a retired service''s orphans:'
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name NOT LIKE 'flyway_schema_history%'
  AND table_name NOT IN ('protocol_definition','action_definition','trigger_index',
                         'protocol_instance','matcher_event_log','step_instance','step_sla_state_transition',
                         'deviation','intelligence_event_log','facility',
                         'protocol_instance_history','step_instance_history')
ORDER BY 1;

\echo '   Ledgers present (the monolith''s own flyway_schema_history normally remains):'
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_name LIKE 'flyway_schema_history%' ORDER BY 1;

\echo '── 2. The 1.x columns must be gone'
SELECT table_name, column_name, 'STILL PRESENT — upgrade did not complete' AS status
FROM information_schema.columns
WHERE table_schema = 'public'
  AND (   (table_name = 'step_instance'          AND column_name IN ('state','completion_status','due_date','overdue_date','missed_date','completed_by_event_id'))
       OR (table_name = 'step_instance_history'  AND column_name IN ('state','completion_status'))
       OR (table_name = 'intelligence_event_log' AND column_name = 'step_state')
       OR (table_name = 'protocol_instance'      AND column_name = 'protocol_canonical')
       OR (table_name = 'deviation'              AND column_name = 'protocol_instance_id'));
\echo '   (no rows above = OK)'

\echo '── 2b. audit_log must be gone (§9)'
SELECT count(*) AS audit_log,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'CHECK' END AS status
FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'audit_log';

\echo '── 3. The 2.0.0 columns must be present, step_status NOT NULL and sla_status nullable'
\echo '   sla_status is nullable by design: null is the absence of a judgement, not a status.'
\echo '   Expect 6 rows — a missing row is as much a failure as a wrong one.'
SELECT table_name||'.'||column_name AS column, is_nullable,
       CASE WHEN column_name = 'step_status' AND is_nullable = 'NO'  THEN 'OK'
            WHEN column_name = 'sla_status'  AND is_nullable = 'YES' THEN 'OK'
            ELSE 'CHECK' END AS status
FROM information_schema.columns
WHERE table_schema = 'public'
  AND (   (table_name = 'step_instance'          AND column_name IN ('step_status','sla_status'))
       OR (table_name = 'step_instance_history'  AND column_name IN ('step_status','sla_status'))
       OR (table_name = 'intelligence_event_log' AND column_name IN ('step_status','sla_status')))
ORDER BY 1;

\echo '── 4. compliance_event_log must have become matcher_event_log'
SELECT
  (SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='matcher_event_log')    AS matcher_event_log,
  (SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='compliance_event_log') AS compliance_event_log,
  CASE WHEN (SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='matcher_event_log')=1
        AND (SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='compliance_event_log')=0
       THEN 'OK' ELSE 'CHECK' END AS status;

\echo '── 5. Every step must hold a legal status pair'
SELECT step_status, sla_status, count(*) AS rows
FROM step_instance GROUP BY 1,2 ORDER BY 1,2;

\echo '── 6. No step may be left with an unmapped status'
\echo '   A null sla_status is legal — it means no threshold has fallen due. PENDING is not: 2.0.0'
\echo '   has no such value, so a surviving PENDING means the mapping in §2 did not run.'
SELECT count(*) AS invalid_rows,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'CHECK' END AS status
FROM step_instance
WHERE step_status IS NULL
   OR step_status NOT IN ('NOT_STARTED','COMPLETED')
   OR (sla_status IS NOT NULL AND sla_status NOT IN ('OVERDUE','MISSED','MET'));

\echo '── 7. SLA schedule: every awaited step with a deadline should have its transition rows'
SELECT t.transition_type, t.is_processed, count(*) AS rows
FROM step_sla_state_transition t GROUP BY 1,2 ORDER BY 1,2;

\echo '── 8. Work the Step SLA Service will pick up on its first sweep'
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

\echo '── 11. matched_event_id must carry the foreign key and partial index V1 declares (§8)'
\echo '   1.x declared neither, and a column rename brings neither with it, so §8 adds them.'
SELECT 'step_instance_matched_event_id_fkey' AS object,
       count(*) AS present,
       CASE WHEN count(*) = 1 THEN 'OK' ELSE 'CHECK' END AS status
FROM pg_constraint
WHERE conrelid = 'step_instance'::regclass AND contype = 'f'
  AND conname = 'step_instance_matched_event_id_fkey'
UNION ALL
SELECT 'idx_step_instance_matched_event', count(*),
       CASE WHEN count(*) = 1 THEN 'OK' ELSE 'CHECK' END
FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'step_instance'
  AND indexname = 'idx_step_instance_matched_event';

\echo '── 12. No step may point at an event that is not in the log'
SELECT count(*) AS orphaned,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'CHECK' END AS status
FROM step_instance s
WHERE s.matched_event_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM matcher_event_log e WHERE e.id = s.matched_event_id);

\echo '── 13. Every CDC-replicated table must be on REPLICA IDENTITY FULL (§7)'
\echo '   The append-only history tables are excluded: they are INSERT-only, so the PK identity is enough.'
SELECT relname AS table, relreplident::text AS replica_identity,
       CASE WHEN relreplident = 'f' THEN 'OK' ELSE 'CHECK' END AS status
FROM pg_class
WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace
  AND relname IN ('protocol_instance','matcher_event_log','step_instance','step_sla_state_transition',
                  'deviation','intelligence_event_log','facility')
ORDER BY 1;
