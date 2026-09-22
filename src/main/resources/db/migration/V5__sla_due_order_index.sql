-- ==============================================================================
-- CCE Matcher Service — order-friendly index for the Step SLA Service's fetch
-- ==============================================================================
-- Flyway Migration: V5
-- Database: PostgreSQL 16
--
-- The Step SLA Service's fetch now joins step_instance into the same query it uses to select due
-- step_sla_state_transition rows (fixing a race where two replicas could each claim a different row of
-- the same step and judge it concurrently — see that service's SlaTransitionFetchRepository). The join
-- makes the existing idx_sslt_due index (on next_attempt_at) less useful: the query orders by
-- process_by, so without an index on that column Postgres reads and sorts the whole unprocessed backlog
-- before applying the batch limit, and now also joins every one of those rows to step_instance before
-- sorting — a cost that grows with backlog size and, on a large step_instance table, can mean touching
-- more of it than fits in cache.
--
-- This index lets Postgres walk process_by in order directly, stopping after one batch instead of
-- scanning and sorting the backlog. It is unrelated to the correctness fix above — it corrects a
-- pre-existing performance characteristic of the fetch query, present regardless of the join — but the
-- two ship together because the join makes the query more sensitive to it, not less.
--
-- CREATE INDEX CONCURRENTLY is used deliberately: on a production-sized step_sla_state_transition table,
-- a plain CREATE INDEX takes a lock that blocks writes (Matcher's own inserts) for the build's duration.
-- CONCURRENTLY avoids that at the cost of a longer build and needing to run outside a transaction, which
-- is why this migration is marked non-transactional below.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sslt_due_order
    ON step_sla_state_transition (process_by)
    WHERE is_processed = FALSE;
