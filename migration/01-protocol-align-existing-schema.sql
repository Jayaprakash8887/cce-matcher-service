-- ==============================================================================
-- CCE Protocol Service — Align an existing (pre-split) ccedb with the V1 shape
-- ==============================================================================
-- Flyway Migration: V2
-- Database: PostgreSQL 16
--
-- V1 is a greenfield migration. A ccedb carried over from the monolithic compliance service already
-- holds these four tables, so V1 is baselined there (spring.flyway.baseline-version = 1) and this
-- migration reconciles the differences instead.
--
-- The definitional tables are otherwise unchanged by the 2.0.0 split — no column of
-- protocol_definition, action_definition or trigger_index moved, was renamed, or changed
-- type. All that differs is five indexes the 1.x schema carried that V1 deliberately does not create,
-- each of them either a prefix of a key that already answers its lookups or an index on a table small
-- enough to be read in a page.
--
-- On a greenfield database this migration is a no-op: none of the indexes it drops were ever created.
-- ==============================================================================

-- The 1.x schema carried two btree indexes on trigger_index that the primary key already serves.
-- trigger_index_pkey is (resource_type, path, code_system, code_value, protocol_definition_id,
-- action_id), so both of these are prefixes of it and PostgreSQL can answer their lookups from the
-- pkey alone. They cost write throughput on every protocol load for no read benefit.
DROP INDEX IF EXISTS idx_trigger_index_code;
DROP INDEX IF EXISTS idx_trigger_index_resource;

-- The third is the GIN index over protocol_definition.definition, which 1.x named
-- idx_protocol_definition_triggers after the trigger extraction that queried the JSON directly. Nothing
-- in 2.0.0 reaches into that JSONB from SQL — triggers are extracted into trigger_index at load time
-- and the definition is parsed in process — so the index is maintained on every protocol load for no
-- read. Both names are dropped: the 1.x one, and the one an earlier build of this release created.
DROP INDEX IF EXISTS idx_protocol_definition_triggers;
DROP INDEX IF EXISTS idx_protocol_definition;

-- And two on action_definition. canonical_url is the leading column of
-- action_definition_url_version_key, which serves both the lookup by canonical URL and the lookup by
-- URL and version, so an index on it alone was always a duplicate. The partial one on status never won
-- either: the table holds tens of rows, and a sequential scan of two pages beats an index scan.
DROP INDEX IF EXISTS idx_action_definition_canonical;
DROP INDEX IF EXISTS idx_action_definition_status;
