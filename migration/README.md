# Database Migration — 1.x → 2.0.0

Everything needed to take a **pre-split `ccedb`** (from the monolithic compliance service) to the
2.0.0 schema, in an environment where you would rather run the change yourself than let the services
do it on startup.

A **new, empty** database needs none of this: start the services in order and Flyway builds the
schema. See [Deployment Guide](../docs/deployment-guide.md).

| File | Purpose |
|---|---|
| `run-upgrade.sh` | Runs the whole upgrade in one transaction, with preconditions and verification |
| `01-protocol-align-existing-schema.sql` | Protocol Service's part — drops five indexes 2.0.0 does not use: two on `trigger_index` and one on `action_definition` that a key already answers, the GIN index over `protocol_definition.definition`, and the partial one on `action_definition.status` |
| `02-matcher-upgrade-from-monolith.sql` | Matcher Service's part — the schema and data transformation |
| `verify.sql` | Post-upgrade checks; run it any time |

> The two `.sql` files are **copies**. The authoritative versions ship inside each service as Flyway
> migrations (`V2__*.sql`), so the services can migrate themselves when that is preferred.
> `run-upgrade.sh` compares the copies against the originals and refuses to run if they have drifted.

---

## What changes

| Before (1.x) | After (2.0.0) |
|---|---|
| `step_instance.state` — one column conflating progress and timeliness | `step_status` (did the work happen?) + `sla_status` (was it on time?) |
| `step_instance.completion_status` — `EARLY`/`ON_TIME`/`LATE` | dropped; derivable from the pair |
| `step_instance.due_date`, `overdue_date`, `missed_date` | moved into `step_sla_state_transition`, one row per threshold |
| `compliance_event_log` | renamed `matcher_event_log` |
| `state`/`completion_status` on `step_instance_history` | `step_status`/`sla_status` |
| `step_state` on `intelligence_event_log` | `step_status`/`sla_status` |
| `step_instance.completed_by_event_id` | renamed `matched_event_id` — the column is set whenever an event matches, and matching is what it records. The rename also adds the foreign key to `matcher_event_log(id)` and the partial index on the column: 1.x declared neither, and renaming a column brings neither with it |
| `protocol_instance.protocol_canonical` | dropped; reached by FK as `protocol_definition.url\|version`, which also gives the version enrolled under rather than the current one |
| `deviation.protocol_instance_id` | dropped; reachable as `step_instance.protocol_instance_id`, which is `NOT NULL` |
| `audit_log` | dropped; the append-only history tables carry state transitions, and actor attribution is planned to move onto the domain tables |

The pair is what makes "completed, but late" representable — the old single column could not express
it. Background: [Architecture Overview §4](../../cce-common-util/docs/architecture-overview.md#4-step-status-and-sla-status).

The last four rows are applied by §8 and §9 of the migration, which run last: they are the final step
of the cutover, after the tables above are in their new shape. `audit_log` is dropped by its own
statement so an operator who wants to retain the 1.x trail can comment out just that one line.

§8 fails with a row count rather than a constraint violation if any `matched_event_id` points at an
event that is no longer in the log — the foreign key cannot be added over those rows, and which rows
to null out is a data decision, not a schema one. Check 12 of `verify.sql` reports them.

### How each old state maps

| Old `state` | `step_status` | `sla_status` | Reasoning |
|---|---|---|---|
| `PENDING` | `NOT_STARTED` | *null* | 2.0.0 has no `PENDING`: a null `sla_status` is the absence of a judgement |
| `DUE` | `NOT_STARTED` | *null* | `DUE` and `PENDING` always meant the same thing, and both are now null |
| `OVERDUE` | `NOT_STARTED` | `OVERDUE` | |
| `MISSED` | `NOT_STARTED` | `MISSED` | |
| `SKIPPED` | `NOT_STARTED` | `MET` | An optional step allowed to lapse breached nothing. It stays `NOT_STARTED` because the work did not happen. 2.0.0 no longer produces this state — optional steps are never pre-created — but the 1.x rows are preserved as they were |
| `COMPLETED` | `COMPLETED` | derived from `completed_at` vs `due_date`/`missed_date` | Timestamps, not `completion_status` — the same judgement the Step SLA Service makes when a threshold falls due |

A completed step with **neither** `completed_at` nor `completion_status` cannot be decided either way.
The migration fails on those rather than guessing, because a wrong `MET` hides a real breach. Resolve
them and re-run.

### The SLA schedule backfill

Each step with a deadline gets its `step_sla_state_transition` rows. A transition whose effect the old
system already applied is inserted **already processed** — leaving it pending would make the Step SLA
Service re-apply it and record a deviation the monolith's scheduler had already recorded.

| Step after mapping | `DUE_DATE_REACHED` | `MISSED_DATE_REACHED` |
|---|---|---|
| `NOT_STARTED`, null sla, deadline ahead | pending | pending |
| `NOT_STARTED`, `OVERDUE` | processed | pending |
| `NOT_STARTED`, `MISSED` | processed | processed |
| `COMPLETED` (any) | processed | processed |

---

## Expected side effect: a burst of OVERDUE deviations

Steps that were sitting in the old `DUE` state become `NOT_STARTED` with a null `sla_status` and a
deadline already in the past, so the Step SLA Service's first sweep moves them to `OVERDUE` and records a deviation.

**This is correct.** `DUE` was not treated as a breach in 1.x; in 2.0.0 a step past its due date and
not started is overdue. Nothing is being double-counted — steps that were already `OVERDUE` or
`MISSED` have their transitions marked processed and are left alone.

Count them in advance with check 8 of `verify.sql`, and warn whoever monitors deviations.

---

## Before you start

1. **Back up `ccedb`.** The migration runs in one transaction and rolls back on failure, but a
   backup is the only thing that protects you from a successful migration you did not want.

   ```bash
   pg_dump -U cce_user -h <host> -p <port> -d ccedb > ccedb_pre_2.0.0_$(date +%Y%m%d).sql
   ```

2. **The source must be at monolith V9 or later.** V9 reversed the direction of
   `PlanDefinition.action.relatedAction` inside `protocol_definition.definition`. Nothing here repeats
   that work — it is data this service does not own. `run-upgrade.sh` checks for it.

3. **Stop the writers.** The old compliance service and the Scheduler Service must both be down. Two
   versions writing `step_instance` across a rename will not end well.

4. **Retire the Scheduler Service.** It is redundant under 2.0.0 — its job is now
   `step_sla_state_transition` plus the Step SLA Service's sweep. It is also *incompatible*: it maps
   `step_instance.state`, `due_date` and `missed_date`, all of which this migration removes, and its
   own `V3` migration creates an index on `state = 'DUE'`. Left running, it will fail rather than sit
   idle. Its topic `cce.scheduler.triggers` has no consumer in 2.0.0, and its tables
   (`scheduler_lease`, `scheduler_scan_cursor`) become orphans — this migration leaves them alone
   rather than dropping another service's data.

5. **Downstream consumers read the old columns.** `cce-data-pipeline` (Debezium → ClickHouse) and
   `cce-insights-service` still select `state`, `completion_status`, `overdue_date`, and the values
   `DUE`/`SKIPPED`. They need updating in step with this migration or analytics will break. That work
   is outside these repositories.

---

## Option A — run it yourself

```bash
# rehearse first: applies everything, then rolls back
./run-upgrade.sh --host db-host --port 5433 --db ccedb --user cce_user --dry-run

# then for real
./run-upgrade.sh --host db-host --port 5433 --db ccedb --user cce_user
```

Password comes from `PGPASSWORD` or a prompt. The script checks preconditions, applies both scripts in
one transaction, baselines each service's Flyway ledger at version 2 so the services do not try to
migrate again, and finishes by running `verify.sql`.

## Option B — let the services do it

Deploy in order with the baseline override set, **for this deployment only**:

```bash
CCE_FLYWAY_BASELINE_VERSION=1   # records V1 as applied, so only V2 runs
```

1. Protocol Service — baselines at 1, applies its V2
2. Matcher Service — baselines at 1, applies its V2
3. Step SLA Service — creates nothing; `ddl-auto: validate` confirms the result

Then **return the variable to 0** (or unset it). Leaving it at 1 would make a future fresh deployment
skip V1 and start against no schema at all.

Verify afterwards:

```bash
psql -h db-host -p 5433 -d ccedb -U cce_user -f verify.sql
```

---

## Rollback

The migration is a single transaction, so a failure leaves the database untouched — nothing to roll
back.

Rolling back a **successful** migration means restoring the backup. It is not reversible in place:
`due_date`, `overdue_date` and `missed_date` are dropped from `step_instance`, and while the values
survive as `process_by` on the transition rows, `overdue_date` has no home in 2.0.0 and is gone.

Restoring also means putting the 1.x services back, since 2.0.0 cannot run against the old schema.

---

## Verified against

PostgreSQL 16. The upgrade was rehearsed from a 1.x database seeded with a row for every old `state`
value plus matching history, deviation and intelligence-event rows, and the result diffed against a
greenfield 2.0.0 schema (Protocol `V1` + Matcher `V1`). The two are **identical** — 101 columns, 33
constraints, 28 indexes, and the same replica identity on every table. Matcher's `V2` is also a clean
no-op against greenfield, which is what lets one chain serve both paths.

Three of those reconciliations exist because the earlier rehearsal missed them: renaming a table or a
column carries neither constraint names nor indexes, so the `matched_event_id` foreign key, the
`matcher_event_log` `processing_status` CHECK, and `REPLICA IDENTITY FULL` all had to be re-established
explicitly (§1, §7, §8). Checks 11-13 of `verify.sql` are what confirm they landed on a real database.

Still to re-confirm on a full 1.x database rather than a seeded fixture: that the Step SLA Service
starts against the upgraded schema under `ddl-auto: validate`, and that its first sweep raises OVERDUE
deviations only for the steps that had been sitting in `DUE`.
