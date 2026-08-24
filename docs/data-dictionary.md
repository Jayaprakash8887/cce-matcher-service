# Data Dictionary — Matcher Service

> The four tables whose JPA entities belong to this service alone.

Nine further tables — `protocol_definition`, `protocol_instance`, `step_instance`,
`step_sla_state_transition`, `deviation`, `trigger_index`, `action_definition` and
`intelligence_event_log` — are mapped by entities in **cce-common-util** and documented there, once:

- [Data Dictionary](../../cce-common-util/docs/data-dictionary.md) — columns, indexes, enums, JSONB shapes, and the full ER diagram
- [Data Dictionary §3](../../cce-common-util/docs/data-dictionary.md#3-ownership) — which service creates and which writes each table

This service **runs the migration** for all thirteen tables except the four the Protocol Service owns,
so it creates more tables than it documents here. Creating a table and mapping it are separate things:
the DDL is in `V1__initial_schema.sql`, the column reference is wherever the entity lives.

---

## 1. Tables owned here

| Table | Purpose | Row Growth |
|---|-------|---------|
| `matcher_event_log` | Lean idempotency log of every inbound CloudEvent and its processing outcome | High (every event) |
| `facility` | Reference lookup of known facilities — auto-populated from inbound event payloads | Low (one row per facility) |
| `protocol_instance_history` | Append-only log of every `protocol_instance.status` transition (CDC → ClickHouse) | High (per status change) |
| `step_instance_history` | Append-only log of the `step_instance.step_status` / `sla_status` transitions **this service makes** — see the coverage caveat in [§4](#4-state-transition-history-tables) (CDC → ClickHouse) | High (per status change) |

These four are the ones no other service reads or writes. `matcher_event_log` in particular is this
service's idempotency guard: nothing outside it has a reason to consult the record of which events
have already been processed.

---

## 2. matcher_event_log

**Lean idempotency log** of every inbound CloudEvent. Records the event source and processing outcome. Used for:
- **Idempotency**: `(cloudevents_id, source)` uniqueness prevents duplicate processing.
- **Auditability**: Links step completions back to the originating event.
- **Troubleshooting**: Payload preservation (optional) enables replay and debugging.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. |
| `cloudevents_id` | `VARCHAR` | **NOT NULL** | — | CloudEvents `id`. Used with `source` for idempotency. |
| `source` | `VARCHAR` | **NOT NULL** | — | CloudEvents `source` (e.g., `rhie-mediator`, `smartcare-emr`). |
| `correlation_id` | `VARCHAR` | Yes | — | Distributed tracing ID from CloudEvent `correlationid` extension. |
| `processing_status` | `VARCHAR` | **NOT NULL** | — | Processing outcome. See [ProcessingStatus](../../cce-common-util/docs/data-dictionary.md#processingstatus). |
| `data` | `JSONB` | Yes | — | Full CloudEvent `data` body (optional, stored for debugging). |
| `received_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Ingestion timestamp. |
| `updated_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Last modification timestamp (e.g., when `processing_status` changes). |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Primary Key | `matcher_event_log_pkey` | `id` |
| Unique | `matcher_event_log_cloudevents_id_source_key` | `(cloudevents_id, source)` — Idempotency guard. |
| Check | — | `processing_status IN ('MATCHED', 'ZERO_MATCH', 'DUPLICATE')` |

### Design Notes

- **Lean design:** Unlike a full audit log, `matcher_event_log` stores only what's needed for idempotency and event-to-step linking. Patient, facility, and action details are not denormalized here — they live in the step instances and audit log.
- **FK from step_instance:** `step_instance.matched_event_id` references `matcher_event_log.id`, linking each completed step to its triggering event.



---

## 3. facility

Reference lookup table of known facilities, auto-populated from inbound FHIR event payloads by the `InboundEventConsumer`. Acts as the authoritative facility registry within the matcher service and is CDC-synced to ClickHouse for analytics.

### Columns

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Surrogate primary key. |
| `facility_id` | `VARCHAR` | **NOT NULL** | — | The bare facility identifier extracted from the FHIR resource location reference (e.g., `"1302"` from `"Location/1302"`). Unique across the table. |
| `facility_name` | `VARCHAR` | Yes | `NULL` | Human-readable facility display name extracted from the FHIR `display` field of the same reference node. Null when the payload carries no display value — updated on the next event that does. |
| `expected_patients_per_day` | `INTEGER` | Yes | `NULL` | Programme-configured daily patient volume baseline. Updated directly in the database by programme staff. Used by analytics MVs to calculate adoption rates. |
| `district_name` | `VARCHAR` | Yes | `NULL` | District the facility belongs to. Not populated by the matcher service — updated directly in the database by programme staff (added in `V7`). |
| `created_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Timestamp of first insertion. |
| `updated_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Timestamp of last modification. |

### Constraints & Indexes

| Type | Name | Columns / Details |
|------|------|-------------------|
| Primary Key | `facility_pkey` | `id` |
| Unique | `facility_facility_id_key` | `facility_id` — Idempotency guard; ensures one row per facility. |

### REPLICA IDENTITY

`REPLICA IDENTITY FULL` is set so Debezium captures the full row on UPDATE/DELETE, enabling correct CDC sync to ClickHouse.

### Auto-population Behaviour

The `InboundEventConsumer` calls `FacilityService.upsertFacility()` for every inbound event before handing off to the matcher engine. The service extracts `facility_id` and `facility_name` from the FHIR payload in a single pass and applies the following upsert rules:

| Scenario | Action |
|----------|--------|
| `facility_id` not resolvable | Skip — nothing is written |
| New facility (id not in table) | INSERT — `facility_name` may be null if no display value found |
| Existing facility, incoming has a name, name differs from stored | UPDATE `facility_name` to incoming value (covers null → name and name → new name) |
| Existing facility, incoming has no name | No-op — stored name is preserved |

Resource-type-specific paths for `facility_id` and `facility_name`, tried in order until one resolves:

| Resource Type | `facility_id` source (priority order) | `facility_name` source |
|---------------|---------------------|----------------------|
| `ServiceRequest` | `locationReference[0].reference` (strip prefix) or `identifier.value` | `locationReference[0].display` |
| `Encounter` | 1. `hospitalization.origin.reference`/`identifier.value` 2. `location[0].location.reference`/`identifier.value` | Display of whichever reference node resolved the id above |
| `Procedure` | `location.reference` (strip prefix) or `identifier.value` | `location.display` |
| `Immunization` | `location.reference` (strip prefix) or `identifier.value` | `location.display` |
| Any other type (`Observation`, `Condition`, `MedicationRequest`, ...) | `source-facility` extension `valueString` — the only signal these resource types carry | `null` (extension has no display) |

Per FHIR R4 (https://hl7.org/fhir/R4/encounter.html), `hospitalization` is only ever populated on a transfer `Encounter` — plain visit/consultation encounters never carry it. For a `TRANSFER_ENCOUNTER`, `location[0].location` holds the transfer **destination**, not the reporting/source facility, so `hospitalization.origin` is checked first and is the correct source facility; `location[0]` is the fallback used by the non-transfer encounter types that have no `hospitalization` at all. The `source-facility` extension is deliberately never consulted for `Encounter` — it does not reliably distinguish origin from destination and is superseded by reading `hospitalization.origin` directly.

If the CloudEvent envelope already carries a `facilityid` extension attribute (set by the emitter), that value is used directly as the ID without re-parsing the payload — but the display name is still resolved from the FHIR body's matching reference node, not assumed to match whatever node the extraction happens to iterate first. Failures are non-fatal — a warning is logged and matcher processing continues unaffected.

### Design Notes

- **Owned by the matcher service:** Unlike the CCE Collector Service, which does not extract facility names, this service is the first point where both the bare ID and display name are available together from the FHIR payload.
- **CDC-synced to ClickHouse:** Via the Debezium connector. Downstream analytics materialized views join against this table for facility-level KPIs and adoption rate calculations.
- **`expected_patients_per_day` is NULL by default:** Programme staff update this column directly in the database once they know the facility's expected volume. The matcher service never writes to this column.
- **`district_name` is NULL by default:** no backfill. Like `expected_patients_per_day`, it is populated directly in the database by programme staff — the matcher service never writes to it.

---

## 4. State-Transition History Tables

Append-only audit logs that record **every** transition of the two UPDATE-in-place lifecycle
columns. They exist because `protocol_instance.status` and `step_instance.state` are overwritten
in place — the prior value is lost — so point-in-time analytics ("what state was this on date D")
and historical rebuilds of the ClickHouse daily-summary MVs are otherwise impossible.

- **Populated at the application layer** by `StateTransitionHistoryService`, invoked from
  `ProtocolInstanceService` / `StepInstanceService` immediately after every status/state write
  (enrollment, event-driven completion, time-driven OVERDUE/MISSED, optional-step close-out). The
  history INSERT runs in the **same transaction** as the parent change (`Propagation.MANDATORY`),
  so it is atomic with the transition — no gaps across the service layer. Caveat: it does **not** capture raw out-of-band SQL UPDATEs — all
  lifecycle mutations must go through the service layer.
- **Append-only:** rows are only ever INSERTed. Never UPDATEd or DELETEd.
- **CDC-synced to ClickHouse** — added to `cce_analytics_pub` and granted in the data-pipeline's
  `cdc/01-configure-replication.sql` (not in the schema migration). Append-only, so the default PK
  replica identity suffices. Not part of the ER diagram — they reference their direct parent by ID
  (`protocol_instance_id` / `step_instance_id`) but enforce no FK.
- **Lean schema — no denormalized grouping keys.** These tables carry only the direct parent id;
  the backfill recovers `protocol_definition_id` (for protocol history) and `protocol_instance_id`
  (for step history) by joining the immutable base tables (`protocol_instance` / `step_instance`).
  Trade-off: if a base row is hard-deleted (only manual/out-of-band SQL does this — the app never
  deletes these rows), its history can no longer be grouped and drops out of the backfill. Accepted:
  a deleted instance is treated as removed from historical rollups too.
- Consumed **only** by the historical-backfill job (`data-pipeline/schema/09-historical-backfill.sql`),
  run after a full re-snapshot. Normal forward operation never reads them.

### protocol_instance_history

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | `BIGSERIAL` | **NOT NULL** | Primary key (insertion order). |
| `protocol_instance_id` | `UUID` | **NOT NULL** | The enrollment whose status changed. Backfill joins `protocol_instance` on this id to recover `protocol_definition_id`. |
| `status` | `VARCHAR` | **NOT NULL** | The status value *after* this transition. See [ProtocolInstanceStatus](../../cce-common-util/docs/data-dictionary.md#protocolinstancestatus). |
| `changed_at` | `TIMESTAMPTZ` | **NOT NULL** | When the transition occurred (`enrolled_at` for the initial enrollment, the transition time for subsequent status changes). |

| Type | Name | Details |
|---|---|---|
| PK | `protocol_instance_history_pkey` | `(id)` |
| Index | `idx_protocol_instance_history_instance` | `(protocol_instance_id, changed_at)` — reconstructing one enrolment's transitions in order |

Bulk reads go through Debezium (snapshot/WAL) and analytical queries run in ClickHouse, so the table
carries no index beyond the one its reconstruction key needs.

### step_instance_history

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | `BIGSERIAL` | **NOT NULL** | Primary key (insertion order). |
| `step_instance_id` | `UUID` | **NOT NULL** | The step whose state changed. Backfill joins `step_instance` on this id to recover `protocol_instance_id`. |
| `step_status` | `VARCHAR` | **NOT NULL** | The step-status value *after* this transition. See [StepStatus](../../cce-common-util/docs/data-dictionary.md#stepstatus). |
| `sla_status` | `VARCHAR` | **NOT NULL** | The SLA-status value *after* this transition. See [SlaStatus](../../cce-common-util/docs/data-dictionary.md#slastatus). |
| `changed_at` | `TIMESTAMPTZ` | **NOT NULL** | When the transition occurred (`created_at` for the initial creation, the transition time thereafter). |

| Type | Name | Details |
|---|---|---|
| PK | `step_instance_history_pkey` | `(id)` |
| Index | `idx_step_instance_history_step` | `(step_instance_id, changed_at)` — same rationale as `protocol_instance_history` |

> **Coverage caveat.** Only the Matcher Service writes this table, so it records the transitions Matcher
> **Two writers.** Matcher records step creation and completion; the **CCE Compliance Service** records
> each `sla_status` it applies (null → `OVERDUE` → `MISSED`, or null → `MET`). Both go through the
> shared `StateTransitionHistoryService`, and append-only is what makes that safe — the two services
> insert disjoint rows and neither updates the other's. Until 2.0.0 only Matcher wrote here, so every
> time-driven transition was missing: a step that went overdue and was never completed had one row
> instead of three. `sla_status` is nullable in this table too, mirroring the column it copies.

---

---

## 5. JSONB Column Schemas

### matcher_event_log — `data`

Contains the full CloudEvent `data` payload (when stored). For FHIR-based events:

```json
{
  "resourceType": "Encounter",
  "id": "9a8e5398-aaaa-4111-84a0-9e1e6e0a0001",
  "status": "finished",
  "type": [{ "coding": [{ "system": "...", "code": "anc-visit" }] }],
  "subject": { "reference": "Patient/260115-0001-7823" }
}
```

For non-FHIR events (e.g., CHW home visits):

```json
{
  "visit_type": "anc-home-visit",
  "status": "completed",
  "chw_id": "CHW-MUSANZE-042",
  "blood_pressure": { "systolic": 135, "diastolic": 85 }
}
```
