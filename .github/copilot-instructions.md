# CCE Matcher Service — AI Agent Instructions

## What this service is

The **event plane** of the CCE system. Spring Boot 3.4.2 / Java 21. Consumes inbound clinical
CloudEvents from Kafka, matches them against FHIR R4 `PlanDefinition` triggers, enrols patients,
creates and completes steps, detects `ORDER_VIOLATION` deviations, and publishes intelligence
triggers.

**No REST API.** Driven entirely by Kafka; the only HTTP it serves is `/actuator`.

Three sibling repositories share the work. Do not implement their concerns here:

| Repository | Owns |
|---|---|
| `cce-protocol-service` | Loading/retiring definitions, building `trigger_index` |
| `cce-compliance-service` | Time-driven SLA transitions and the `OVERDUE`/`MISSED` deviations they raise |
| `cce-common-util` | Shared entities, repositories, FHIR parser, `IntelligenceActionEvaluator`, exception handler |

`cce-common-util` is a Gradle **composite build** (`includeBuild '../cce-common-util'`), so it must be
checked out as a sibling directory. Editing a shared entity there affects all three services.

## Read these first

Documentation is not duplicated between repositories. Start here:

- `docs/architecture-overview.md` — core pipeline, two-tier matching, step lifecycle, backfill, flat step model
- `docs/kafka-events.md` — topics, CloudEvents schemas, consumer/producer patterns
- `docs/data-dictionary.md` — only the four tables whose entities live here
- `../cce-common-util/docs/architecture-overview.md` — why the services are split, the SLA contract, deployment order
- `../cce-common-util/docs/data-dictionary.md` — the nine shared tables, enums, JSONB shapes, table ownership
- `../cce-common-util/docs/fhir-conformance.md` — `relatedAction` direction, status vocabularies, triggers, timing units

## Conventions

- **Package** `org.openphc.cce.matcher` — 28 source files
- **Entities**: only four are local (`Facility`, `MatcherEventLog`, `ProtocolInstanceHistory`,
  `StepInstanceHistory`). The other nine live in `cce-common-util` — never redeclare one here
- **Enums**: all in `cce-common-util`. There is no local enums package
- **Timestamps**: `OffsetDateTime` in UTC (`hibernate.jdbc.time_zone=UTC`)
- **IDs**: UUIDv7 via the shared generator; `TriggerIndex` uses a composite key
- **JSONB**: `JsonNode` with `@JdbcTypeCode(SqlTypes.JSON)`
- **Migrations**: Flyway only, `ddl-auto: validate`. This service's ledger is
  `flyway_schema_history_matcher`

## Invariants — breaking these corrupts data

1. **Write `step_status`, never let anything else.** Conversely, only settle `sla_status` at
   completion; the Compliance Service owns it as deadlines pass. The two services must never write
   the same column — see `../cce-common-util/docs/data-dictionary.md#3-ownership`.
2. **Insert `step_sla_state_transition` rows in the same transaction as the step.** A step must never
   exist without its schedule, or its deadlines are invisible forever.
3. **Never update a transition row after inserting it.** Claim and processing columns belong to the
   Compliance Service.
4. **`relatedAction` names a step's prerequisite, not its successor**, and both `after-*` and
   `before-*` families occur in real protocols. Getting this backwards inverts every dependency —
   see `../cce-common-util/docs/fhir-conformance.md#1-relatedaction-direction`.
5. **Resource type comes from `data.resourceType` in the payload**, never from the CloudEvents
   envelope `type`.
6. **Timing is judged against clinical occurrence time**, not ingestion time. An act performed on
   time but reported late is still `MET`.

## Design patterns specific to this service

- **Two-tier matching**: Tier 1 is a `trigger_index` lookup with `GROUP BY` + `HAVING` to enforce AND
  semantics across multiple `codeFilter` entries. Tier 2 evaluates JSONLogic/FHIRPath. The Tier 1
  result is reused rather than re-queried.
- **Condition-only triggers** (no `data[]`) are held in memory and evaluated per event.
- **Explicit matching**: a CloudEvent carrying a non-null `actionId` extension bypasses both tiers.
- **Every match creates its own step** — there is no ambiguous state.
- **Progressive instantiation**: completing a step creates its dependents using `relatedAction`
  offsets. Works *forward* only.
- **Backfill**: because progressive instantiation is forward-only, a step created reactively from its
  own trigger leaves its unreported mandatory predecessors with no row at all.
  `backfillMissingMandatorySteps` creates them, and must run *after* order-violation detection so it
  does not invent a violation.
- **Flat sub-step model**: nested `action.action[]` step entries are flattened to peers at parse time.
  Action ids are therefore unique document-wide.
- **Protocol cache reconciliation**: definitional changes are **polled**, not pushed. A newly loaded
  protocol takes up to `cce.protocol.refresh-interval-ms` to start matching.
- **Kafka**: `AckMode.RECORD`; failures go to `DefaultErrorHandler`, retry with backoff, then
  `<topic>.dlq`. `cce.kafka.retry.max-attempts` counts *deliveries*, so it is passed to `FixedBackOff`
  as `maxAttempts - 1`.

## Build & test

```bash
./gradlew build                     # unit + integration tests + coverage gate
./gradlew test                      # 206 unit tests
./gradlew integrationTest           # 10 tests — EmbeddedKafka + H2
./gradlew test --tests MatcherEngineTest
./gradlew jacocoTestReport
```

Coverage gate: **0.97** instruction coverage, excluding `MatcherServiceApplication`.

Local run needs shared infrastructure and the schema:

```bash
cd ../cce-collector-service && docker compose up -d   # PostgreSQL + Kafka
cd ../cce-protocol-service && ./gradlew bootRun       # must migrate ccedb first
cd ../cce-matcher-service  && ./gradlew bootRun
curl localhost:8091/actuator/health
```

Docker images build from the **workspace** directory, not the repository, because of the composite
build: `docker build -f cce-matcher-service/Dockerfile -t cce-matcher-service:2.0.0 .`

## Not in scope

- Loading, validating or retiring definitions — `cce-protocol-service`
- Time-driven SLA transitions and `OVERDUE`/`MISSED` deviations — `cce-compliance-service`
- Intelligence delivery and routing — the CCE Intelligence Service consumes
  `cce.intelligence.triggers`
- CQL expression evaluation — only JSONLogic and FHIRPath are supported
