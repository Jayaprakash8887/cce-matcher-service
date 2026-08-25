# Data Dictionary — Matcher Service

> The two tables whose JPA entities belong to this service alone.

Ten further tables — `protocol_definition`, `protocol_instance`, `step_instance`,
`step_sla_state_transition`, `deviation`, `trigger_index`, `action_definition`,
`intelligence_event_log` and the two state-transition history tables — are mapped by entities in
**cce-common-util** and documented there, once:

- [Data Dictionary](../../cce-common-util/docs/data-dictionary.md) — columns, indexes, enums, JSONB shapes, and the full ER diagram
- [Data Dictionary §3](../../cce-common-util/docs/data-dictionary.md#3-ownership) — which service creates and which writes each table

This service **runs the migration** for every table except the three the Protocol Service owns, so it
creates far more tables than it documents here. Creating a table and mapping it are separate things:
the DDL is in `V1__initial_schema.sql`, the column reference is wherever the entity lives.

The history tables moved out in 2.0.0. Their entities now live in cce-common-util, because the
Compliance Service also appends to `step_instance_history` — recording each `sla_status` transition it
applies — so they stopped belonging to this service alone. This service still runs their DDL. See
[Data Dictionary §12](../../cce-common-util/docs/data-dictionary.md#12-state-transition-history-tables).

---

## 1. Tables owned here

| Table | Purpose | Row Growth |
|---|-------|---------|
| `matcher_event_log` | Lean idempotency log of every inbound CloudEvent and its processing outcome | High (every event) |
| `facility` | Reference lookup of known facilities — auto-populated from inbound event payloads | Low (one row per facility) |

No other service reads or writes either one. `matcher_event_log` in particular is this service's
idempotency guard: nothing outside it has a reason to consult the record of which events have already
been processed.

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

## 4. JSONB Column Schemas

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
