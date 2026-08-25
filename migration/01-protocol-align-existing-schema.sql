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
-- type. What differs is index names: two the 1.x schema carried that V1 deliberately does not
-- create, and one that V1 creates under a different name.
--
-- On a greenfield database this migration is a no-op: the indexes it drops were never created, and
-- the one it renames already carries its V1 name.
-- ==============================================================================

-- The 1.x schema carried two btree indexes on trigger_index that the primary key already serves.
-- trigger_index_pkey is (resource_type, path, code_system, code_value, protocol_definition_id,
-- action_id), so both of these are prefixes of it and PostgreSQL can answer their lookups from the
-- pkey alone. They cost write throughput on every protocol load for no read benefit.
DROP INDEX IF EXISTS idx_trigger_index_code;
DROP INDEX IF EXISTS idx_trigger_index_resource;

-- The GIN index on protocol_definition.definition is idx_protocol_definition_triggers in the 1.x
-- schema, named after the trigger extraction that once queried the JSON directly. V1 creates it as
-- idx_protocol_definition, so an upgraded database has to be brought to that name or the two schemas
-- are not the same schema — a rename nothing else performs, since renaming neither a table nor a
-- column carries index names with it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class
               WHERE relkind = 'i' AND relname = 'idx_protocol_definition_triggers'
                 AND relnamespace = 'public'::regnamespace)
       AND NOT EXISTS (SELECT 1 FROM pg_class
                       WHERE relkind = 'i' AND relname = 'idx_protocol_definition'
                         AND relnamespace = 'public'::regnamespace)
    THEN
        ALTER INDEX idx_protocol_definition_triggers RENAME TO idx_protocol_definition;
        RAISE NOTICE 'Renamed idx_protocol_definition_triggers to idx_protocol_definition';
    END IF;
END $$;

-- And create it outright for a database that carried neither name. Idempotent, so this is also the
-- no-op that leaves a greenfield database alone.
CREATE INDEX IF NOT EXISTS idx_protocol_definition
    ON protocol_definition USING GIN (definition jsonb_path_ops);
