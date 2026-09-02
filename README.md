# CCE Matcher Service

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2-green)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)
![Kafka](https://img.shields.io/badge/Kafka-3.x%20KRaft-orange)
![License](https://img.shields.io/badge/license-proprietary-lightgrey)

A core microservice within the CCE platform. It tracks patient adherence to clinical protocols defined as FHIR R4 `PlanDefinition` resources — consuming clinical events, matching them against protocol steps (including nested sub-steps flattened to peers), detecting deviations, evaluating intelligence actions, and publishing intelligence triggers for downstream processing.

This is an **event-driven service with no REST API**. It is driven entirely by Kafka, and reads
`protocol_definition`, `trigger_index` and `action_definition` — all owned and written by the
**cce-protocol-service**. The only HTTP it serves is `/actuator` (health probes and Prometheus).

## Quick Start

> Requires **`cce-common-util` checked out as a sibling directory** — `settings.gradle` declares
> `includeBuild '../cce-common-util'`, so the build cannot resolve it otherwise.

```bash
# Start shared infrastructure (from collector service)
cd /path/to/cce-collector-service && docker compose up -d

# Build (compiles cce-common-util first, then this service)
cd /path/to/cce-matcher-service
./gradlew build

# Run (Flyway applies migrations to shared ccedb database)
./gradlew bootRun

# Health check (local default port is 8091; the Docker image pins SERVER_PORT=8080)
curl localhost:8091/actuator/health
```

## Documentation

| Document | Description |
|---|---|
| [Architecture & Design](docs/architecture-overview.md) | Core pipeline, two-tier matching, step lifecycle, progressive instantiation and backfill, flat step model |
| [Data Dictionary](docs/data-dictionary.md) | The two tables whose entities belong to this service — `matcher_event_log` and `facility` |
| [Kafka Events](docs/kafka-events.md) | Topics, CloudEvents message formats, consumers, and producers |
| [Flow Diagrams](docs/flow-diagrams.md) | Sequence and flow diagrams for the major workflows |
| [Developer Setup](docs/developer-setup.md) | Prerequisites, build instructions, and local development configuration |
| [Deployment Guide](docs/deployment-guide.md) | Production deployment, environment variables, Docker/K8s, monitoring |
| [Sample PlanDefinition](docs/sample-plan-definition.md) | A comprehensive worked example with companion ActivityDefinitions |

System-wide context lives in **cce-common-util** and is not restated here:

| For | See |
|---|---|
| Why the services are split, and how they coordinate | `cce-common-util` → [docs/architecture-overview.md](../cce-common-util/docs/architecture-overview.md) |
| Schema for the ten shared tables (including the two history tables), enums, JSONB shapes, table ownership | `cce-common-util` → [docs/data-dictionary.md](../cce-common-util/docs/data-dictionary.md) |
| `relatedAction` direction, status vocabularies, triggers, timing units | `cce-common-util` → [docs/fhir-conformance.md](../cce-common-util/docs/fhir-conformance.md) |
| The shared entities, parser, cache and evaluator this service uses | `cce-common-util` → [docs/library-reference.md](../cce-common-util/docs/library-reference.md) |
| Loading and retiring definitions, building the trigger index | `cce-protocol-service` → [docs/](../cce-protocol-service/docs/architecture-overview.md) |
| Applying SLA transitions once they fall due | `cce-compliance-service` → [docs/](../cce-compliance-service/docs/architecture-overview.md) |

Cross-repository links assume the repositories are checked out as siblings, which is also what the
Gradle composite build assumes.

## Architecture

`*` marks a component supplied by `cce-common-util` rather than defined in this repo.

```
cce.events.inbound → InboundEventConsumer → MatcherEngine
                                              ├── Idempotency (MatcherEventLogService)
                                              ├── Resource Extraction (ResourceInfoExtractor)
                                              ├── Tier 1 Matching (TriggerMatchingService → trigger_index)
                                              ├── Tier 2 Evaluation (FhirExpressionEvaluator *)
                                              ├── Enrollment (ProtocolInstanceService)
                                              ├── Step Management (StepInstanceService)
                                              │   ├── Flat step model (sub-steps flattened to peers)
                                              │   ├── SLA schedule (StepSlaScheduleService)
                                              │   ├── Order-violation detection (DeviationRecorder *)
                                              │   └── Intelligence Evaluation (IntelligenceActionEvaluator *)
                                              ├── Intelligence Publishing (IntelligenceTriggerProducer *
                                              │                            → cce.intelligence.triggers)
                                              └── State history (StateTransitionHistoryWriter *)

Startup + every 60s: ProtocolDefinitionService.refreshProtocolCaches()
                       └── reconciles ParsedProtocolCache * + condition-only triggers against the DB
```

**Owned elsewhere.** Protocol and action-definition management (writes to `protocol_definition`,
`trigger_index`, `action_definition`) belongs to the CCE Protocol Service.

`step_instance.sla_status` belongs entirely to the **CCE Compliance Service**, which claims the
`step_sla_state_transition` rows this service writes. This service records *that* a step completed and
*when* (`completed_at`, from the clinical occurrence time) and never judges whether that was timely —
so a freshly completed step's `sla_status` is null until Compliance's next sweep, which compares the
`completed_at` recorded here against the step's thresholds. One writer, one source of evidence.

## Testing

```bash
# Unit tests (198 tests — shared-code tests live in cce-common-util)
./gradlew test

# Integration tests (9 tests, EmbeddedKafka + H2)
./gradlew integrationTest

# Full build — runs unit tests, integration tests, and the coverage gate
./gradlew build

# Coverage report
./gradlew test jacocoTestReport
```
