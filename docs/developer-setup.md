# Developer Setup & Configuration

## 1. Prerequisites

| Tool | Version | Required | Purpose |
|---|---|---|---|
| **Java JDK** | 21 LTS | Yes | Build and runtime |
| **Gradle** | 8.x | Yes | Build tool (via wrapper) |
| **Docker** | 24+ | Recommended | Run PostgreSQL, Kafka locally |
| **Docker Compose** | 2.x | Recommended | Orchestrate infrastructure |
| **PostgreSQL** | 16+ | Yes | Primary database |
| **Apache Kafka** | 3.x | Yes | Message broker |
| **Git** | 2.x | Yes | Version control |
| **`cce-common-util`** | matching branch | Yes | Sibling checkout — shared entities, enums, Kafka contracts and FHIR layer, wired in via `includeBuild` |

## 2. Quick Start

### 2.1 Clone & Build

This repo is one half of a composite Gradle build. `settings.gradle` declares
`includeBuild '../cce-common-util'`, so **the shared module must be checked out as a sibling directory**
— the build cannot resolve `org.openphc.cce:cce-common-util` otherwise.

```bash
# Both repos side by side — the relative path in settings.gradle depends on it
mkdir -p ~/workspace && cd ~/workspace
git clone <url>/cce-common-util
git clone <url>/cce-matcher-service

cd cce-matcher-service
./gradlew build          # compiles cce-common-util first, then this service
```

Expected layout:

```
workspace/
├── cce-common-util/       # shared entities, enums, Kafka contracts, FHIR layer
└── cce-matcher-service/   # this repo  → settings.gradle: includeBuild '../cce-common-util'
```

There is no publish step: a change in `cce-common-util` is compiled into the next build of this service
directly. If the build fails with `Could not resolve org.openphc.cce:cce-common-util`, the sibling
checkout is missing or sits at a different relative path.

```bash
# Skip tests for fast iteration
./gradlew build -x test
```

### 2.2 Start Infrastructure

PostgreSQL, Kafka, and the shared database (`ccedb`) are deployed by the **CCE Collector Service**. All CCE services share the same database.

```bash
# Start shared infrastructure (PostgreSQL on port 5433 + Kafka on port 9092)
cd /path/to/cce-collector-service
docker compose up -d

# Verify shared services are running
docker compose ps
```

### 2.3 Run the Application

```bash
# Using Gradle
./gradlew bootRun

# Or using the JAR
java -jar build/libs/cce-matcher-service-2.0.0.jar

# With custom configuration
DB_HOST=localhost DB_PORT=5433 java -jar build/libs/cce-matcher-service-2.0.0.jar
```

### 2.4 Verify Health

```bash
# Health check (default local port is 8091 unless SERVER_PORT is overridden)
curl http://localhost:8091/actuator/health

# Expected response
# {"status":"UP","components":{"db":{"status":"UP"},"kafka":{"status":"UP"},"diskSpace":{"status":"UP"}}}
```

## 3. Configuration Reference

### 3.1 Environment Variables

All configuration can be overridden via environment variables:

#### Database

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5432` | PostgreSQL port. The collector service exposes Postgres on `5433`, so set `DB_PORT=5433` for local dev against shared infrastructure |
| `DB_NAME` | `ccedb` | Shared database name (all CCE services) |
| `DB_USERNAME` | `cce_user` | Database username (shared with collector service) |
| `DB_PASSWORD` | `cce_pass` | Database password (shared with collector service) |
| `DB_POOL_SIZE` | `20` | HikariCP max pool size |
| `DB_POOL_MIN_IDLE` | `5` | HikariCP minimum idle connections |
| `DB_CONNECTION_TIMEOUT` | `30000` | HikariCP connection timeout (ms) |
| `DB_IDLE_TIMEOUT` | `600000` | HikariCP idle timeout (ms) |
| `DB_MAX_LIFETIME` | `1800000` | HikariCP max connection lifetime (ms) |

#### Kafka

| Variable | Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker addresses |

#### Server

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8091` | Application port |

### 3.2 Kafka Topic Configuration

Configured via `cce.kafka.topics.*` in `application.yml`:

| Property | Default Value | Description |
|---|---|---|
| `cce.kafka.topics.inbound-events` | `cce.events.inbound` | Inbound clinical events |
| `cce.kafka.topics.intelligence-triggers` | `cce.intelligence.triggers` | Outbound intelligence trigger events |
| `cce.protocol.parsed-cache-size` | `256` | Parsed protocol definitions (flattened steps + dependency graph) held in memory |
| `cce.protocol.refresh-interval-ms` | `60000` | How often the in-memory protocol caches reconcile against the database |
| `cce.intelligence.publish-confirm-timeout-ms` | `5000` | How long to wait for Kafka to acknowledge an intelligence trigger |

### 3.3 JPA & Hibernate

| Property | Value | Description |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `none` | Schema managed entirely by Flyway; Hibernate performs no DDL or validation |
| `spring.jpa.open-in-view` | `false` | Prevents lazy loading in controllers (best practice) |
| `hibernate.dialect` | `PostgreSQLDialect` | Not set explicitly in `application.yml` — auto-detected by Spring Boot from the PostgreSQL driver |
| `hibernate.jdbc.time_zone` | `UTC` | All timestamps in UTC |

### 3.4 Flyway

| Property | Value | Description |
|---|---|---|
| `spring.flyway.enabled` | `true` | Auto-apply migrations on startup |
| `spring.flyway.locations` | `classpath:db/migration` | Migration file location |
| `spring.flyway.baseline-on-migrate` | `true` | Creates the history table on first run |
| `spring.flyway.baseline-version` | `${CCE_FLYWAY_BASELINE_VERSION:0}` | Selects which of the two paths below applies |
| `spring.flyway.out-of-order` | `true` | Tolerates a migration landing behind one already applied |
| `spring.flyway.table` | `flyway_schema_history_matcher` | Namespaced history table so each CCE service tracks its own migrations in the shared database |

There are two migrations, and one chain serves both a new database and one carried over from the
pre-split monolith:

| Migration | On a new database | On a 1.x (pre-split) `ccedb` |
|---|---|---|
| `V1__initial_schema.sql` | Creates the whole 2.0.0 schema | **Skipped** — recorded as already applied |
| `V2__upgrade_from_monolith_schema.sql` | No-op — every block is guarded on the presence of the 1.x shape | Performs the transformation (splits `state` into `step_status`/`sla_status`, moves the deadlines into `step_sla_state_transition`, renames `compliance_event_log`) |

**New database:** leave `CCE_FLYWAY_BASELINE_VERSION` at `0`. V1 builds the schema, V2 finds nothing to
change.

**Upgrading an existing 1.x database:** set `CCE_FLYWAY_BASELINE_VERSION=1` for that one deployment, so
Flyway records V1 as applied and runs only V2. Return it to `0` afterwards. This cannot be auto-detected:
by the time this service migrates, the Protocol Service has already created its tables, so an
"is the schema empty?" check would never be true here.

> **Precondition for the upgrade path:** the source database must already be at monolith `V9` or later.
> `V9` reversed the direction of `relatedAction` inside `protocol_definition.definition`, and `V2` does
> not repeat that work — the JSON is data this service does not own.

The repo-root [`migration/`](../migration/) folder holds the runnable equivalents (`run-upgrade.sh`,
`verify.sql`) for operating that upgrade outside application startup.

### 3.5 Observability

| Property | Value | Description |
|---|---|---|
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Exposed actuator endpoints |
| `management.metrics.tags.application` | `cce-matcher-service` | Common metric tag |

There is no tracing configuration — no tracing backend is on the classpath. Correlation is via the MDC
`correlationId` in log output.

## 4. Project Structure

Two repos, side by side, joined by `includeBuild`:

```
workspace/
├── cce-common-util/                      # shared module (own repo)
│   └── src/main/java/org/openphc/cce/common/
│       ├── entity/       # shared JPA entities (StepInstance, StepSlaStateTransition, …)
│       ├── enums/        # the shared status vocabulary
│       ├── repository/   # repositories over the shared entities
│       ├── event/        # CloudEventMessage, IntelligenceTriggerEvent (Kafka contracts)
│       ├── kafka/        # IntelligenceTriggerProducer, KafkaTopicProperties
│       ├── fhir/         # PlanDefinitionParser, ParsedProtocolCache, expression evaluation
│       ├── service/      # AuditService, DeviationService, IntelligenceActionEvaluator,
│       │                 #   ActionDefinitionResolver, SlaThresholdReader
│       ├── config/       # AppConfig, FhirConfig, KafkaRetryProperties
│       ├── web/          # ErrorResponse, GlobalExceptionHandler
│       └── support/      # UuidV7Generator
│
└── cce-matcher-service/                  # this repo
    ├── docs/                             # documentation (this folder)
    ├── migration/                         # runnable 1.x → 2.0.0 upgrade (run-upgrade.sh, verify.sql)
    ├── src/
    │   ├── main/
    │   │   ├── java/org/openphc/cce/matcher/
    │   │   │   ├── MatcherServiceApplication.java   # scans org.openphc.cce (both packages)
    │   │   │   ├── config/          # KafkaConfig, ObservabilityConfig
    │   │   │   ├── domain/          # Matcher-owned tables + their repositories
    │   │   │   ├── kafka/consumer/  # InboundEventConsumer
    │   │   │   └── service/         # MatcherEngine + the matching pipeline
    │   │   └── resources/
    │   │       ├── application.yml
    │   │       └── db/migration/    # V1__initial_schema.sql, V2__upgrade_from_monolith_schema.sql
    │   ├── test/                    # unit tests
    │   └── integrationTest/         # EmbeddedKafka + H2
    ├── Dockerfile                   # multi-stage build
    ├── build.gradle
    └── settings.gradle              # includeBuild '../cce-common-util'
```

> **Build from the workspace directory, not this repo.** `Dockerfile` copies both repositories, and
> Docker `COPY` cannot reach outside its build context. Building with this repo as the context fails in
> stage 1 with `Included build '/.../cce-common-util' does not exist`.

## 5. Database Setup

All CCE services share the same database (`ccedb`) on the PostgreSQL instance deployed by the CCE Collector Service (port `5433`, user `cce_user`). Each service owns its own tables — Flyway migrations are namespaced to avoid conflicts.

### 5.1 No Separate Database Creation Needed

The database is created by the collector service's Docker Compose. The matcher service only runs its Flyway migrations on startup.

### 5.2 Flyway Migrations

Migrations are applied automatically on application startup via Spring Boot's Flyway autoconfiguration. There is no Flyway Gradle plugin in `build.gradle` (only the `flyway-core` and `flyway-database-postgresql` libraries used at runtime), so `flywayMigrate` / `flywayInfo` Gradle tasks are **not** available. To inspect or run migrations manually, use the Flyway CLI or psql directly against the `flyway_schema_history_matcher` table:

```bash
# Inspect applied migrations directly
psql -h localhost -p 5433 -U cce_user -d ccedb \
     -c "SELECT * FROM flyway_schema_history_matcher ORDER BY installed_rank"
```

### 5.3 Current Migrations

| Version | Description | Script |
|---|---|---|
| V1 | Initial schema — 12 tables with their indexes and constraints | `V1__initial_schema.sql` |

## 6. Docker Build

### 6.1 Build Image

```bash
# Build the Docker image
cd ..   # the workspace directory holding both repos
docker build -f cce-matcher-service/Dockerfile -t cce-matcher-service:latest .

# Run the container
# The image sets SERVER_PORT=8080 itself, matching its EXPOSE/healthcheck port
# (the application's own default, outside Docker, is 8091)
docker run -d \
  --name matcher-service \
  -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5433 \
  -e DB_NAME=ccedb \
  -e DB_USERNAME=cce_user \
  -e DB_PASSWORD=cce_pass \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  cce-matcher-service:latest
```

### 6.2 Dockerfile Overview

```
Stage 1: Build (eclipse-temurin:21-jdk-alpine)
  → Copy build.gradle, settings.gradle, download dependencies
  → Copy source, run ./gradlew build -x test

Stage 2: Runtime (eclipse-temurin:21-jre-alpine)
  → Create non-root user 'cce' (UID 1001)
  → Copy JAR from build stage
  → JVM flags: none baked in — entrypoint runs `java $JAVA_OPTS -jar app.jar`, so flags are supplied via the JAVA_OPTS env var at runtime
  → Healthcheck: wget to /actuator/health every 30s
  → Expose port 8080
```

## 7. Key Build Commands

| Command | Purpose |
|---|---|
| `./gradlew build -x test` | Build without tests |
| `./gradlew build` | Build + run unit tests |
| `./gradlew test` | Run unit tests only |
| `./gradlew integrationTest` | Run integration tests (EmbeddedKafka + H2) |
| `./gradlew test jacocoTestReport` | Unit tests + coverage report |
| `./gradlew dependencies` | Show dependency tree |
| `./gradlew bootRun` | Run application via Gradle |

## 8. Testing

### 8.1 Test Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ |
| `spring-kafka-test` | Kafka test utilities |
| `awaitility` | Async assertions (integration tests only) |

### 8.2 Test Categories

| Category | Location | Infrastructure |
|---|---|---|
| Unit tests | `src/test/java` | Mocked dependencies |
| Integration tests | `src/integrationTest/java` | EmbeddedKafka + H2 in-memory (PostgreSQL mode) |

### 8.3 Running Tests

```bash
# Unit tests (206 tests — shared-code tests live in cce-common-util)
./gradlew test

# Integration tests (10 tests — EmbeddedKafka + H2)
./gradlew integrationTest

# Specific test class
./gradlew test --tests MatcherEngineTest

# Full build — unit tests, integration tests, and the coverage gate
./gradlew build

# With test coverage
./gradlew test jacocoTestReport
```

## 9. IDE Setup

### 9.1 IntelliJ IDEA

1. Import as Gradle project
2. Set JDK to 21
3. Enable annotation processing (required for Lombok, used throughout the domain and Kafka model layers)
4. Configure Spring Boot run configuration:
   - Main class: `org.openphc.cce.matcher.MatcherServiceApplication`
   - Active profiles: `local` (if needed)
   - Environment variables: as listed in Section 3.1

### 9.2 VS Code

1. Install "Extension Pack for Java" and "Spring Boot Extension Pack"
2. Open the project folder
3. VS Code auto-detects the Gradle project
4. Use the Spring Boot Dashboard to run/debug

## 10. Logging

### 10.1 Log Format

```
2026-03-15 10:30:00.123 [kafka-consumer-1] [corr-abc123] INFO MatcherEngine - Processing inbound event...
```

Format: `timestamp [thread] [correlationId] level logger - message`

### 10.2 Log Levels

| Logger | Default Level | Description |
|---|---|---|
| `org.openphc.cce.matcher` | `INFO` | Application logs |
| `org.springframework.kafka` | `WARN` | Kafka framework logs |
| `org.hibernate.SQL` | `WARN` | SQL statement logs |

### 10.3 Adjusting Log Levels

```bash
# Via environment variable
LOGGING_LEVEL_ORG_OPENPHC_CCE_MATCHER=DEBUG java -jar build/libs/cce-matcher-service-2.0.0.jar

# Via application.yml override
# logging.level.org.openphc.cce.matcher: DEBUG
```

## 11. Troubleshooting

### 11.1 Common Issues

| Issue | Cause | Solution |
|---|---|---|
| `Connection refused: localhost:5433` | PostgreSQL not running | Start collector service infrastructure: `cd cce-collector-service && docker compose up -d` |
| `Connection refused: localhost:9092` | Kafka not running | Start Kafka or Docker container |
| `Flyway migration failed` | Schema conflicts | Check migration scripts; there is no Flyway Gradle plugin in this project, so reset manually (dev only) by dropping the affected tables and `flyway_schema_history_matcher` rows via psql |
| `Deserialization error` | Message format mismatch | Check producer serialization, trusted packages |
| Build fails with `javac not found` | JDK not installed (JRE only) | Install JDK 21 or use Docker build |

### 11.2 Useful Diagnostic Commands

```bash
# Check application health (default local port is 8091 unless SERVER_PORT is overridden)
curl -s http://localhost:8091/actuator/health | jq .

# View application metrics
curl -s http://localhost:8091/actuator/metrics | jq .

# Check specific metric
curl -s http://localhost:8091/actuator/metrics/cce.events.processed | jq .

# View Prometheus metrics
curl http://localhost:8091/actuator/prometheus

# Check database connectivity
psql -h localhost -p 5433 -U cce_user -d ccedb -c "SELECT 1"

# Check Kafka topics
kafka-topics.sh --bootstrap-server localhost:9092 --list

# Check consumer group lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group cce-matcher-service --describe
```

## 12. Security Notes for Development

This service exposes no application API, so there is nothing to authenticate. Its inputs are Kafka topics and the shared database; the only HTTP surface is `/actuator` (health and Prometheus).

Note that it holds write access to the shared `ccedb` and reads protocol definitions written by another service — so database credentials remain the sensitive material here, not request-level auth.
