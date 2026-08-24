# Deployment Guide

> **CCE Matcher Service** — Production deployment reference  
> **Version:** 1.0.0 | **Java:** 21 LTS | **Spring Boot:** 3.4.2

---

## Table of Contents

1. [Infrastructure Requirements](#1-infrastructure-requirements)
2. [Environment Variables](#2-environment-variables)
3. [Docker Deployment](#3-docker-deployment)
4. [Kubernetes Deployment](#4-kubernetes-deployment)
5. [Database Setup](#5-database-setup)
6. [Kafka Setup](#6-kafka-setup)
7. [JVM Tuning](#7-jvm-tuning)
8. [Health Checks & Monitoring](#8-health-checks--monitoring)
9. [Backup & Recovery](#9-backup--recovery)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Infrastructure Requirements

| Component | Version | Purpose | Notes |
|---|---|---|---|
| **Java JDK** | 21 LTS | Runtime | Eclipse Temurin recommended |
| **PostgreSQL** | 16+ | Primary database | JSONB support required |
| **Apache Kafka** | 3.x | Message broker | KRaft mode (no Zookeeper) |
| **Docker** | 24+ | Container runtime | Optional if running natively |

### Resource Recommendations (Production)

| Resource | Minimum | Recommended |
|---|---|---|
| CPU | 2 cores | 4 cores |
| Memory | 1 GB | 2 GB |
| Disk (app) | 500 MB | 1 GB |
| Disk (PostgreSQL) | 10 GB | 50 GB+ |
| Network | 100 Mbps | 1 Gbps |

---

## 2. Environment Variables

All configuration is externalized via environment variables. Defaults are provided for local development.

### Application

| Variable | Default | Required | Description |
|---|---|---|---|
| `SERVER_PORT` | `8091` (`8080` in Docker) | No | HTTP server port. The Docker image sets `SERVER_PORT=8080` to match its `EXPOSE`/`HEALTHCHECK`, so containerised deployments need no override; set it only to move off 8080, and change the published port with it |
| `SPRING_PROFILES_ACTIVE` | — | Yes (prod) | Set to `prod` for production |

### Database

| Variable | Default | Required | Description |
|---|---|---|---|
| `DB_HOST` | `localhost` | Yes | PostgreSQL host (shared with collector service) |
| `DB_PORT` | `5432` | No | PostgreSQL port (standard PostgreSQL default in code — set to `5433` when connecting to the shared instance deployed by the collector service) |
| `DB_NAME` | `ccedb` | No | Shared database name (all CCE services) |
| `DB_USERNAME` | `cce_user` | Yes | Database username (shared with collector service) |
| `DB_PASSWORD` | `cce_pass` | Yes | Database password (shared with collector service) |
| `DB_POOL_SIZE` | `20` (dev) / `30` (prod) | No | HikariCP maximum pool size |
| `DB_POOL_MIN_IDLE` | `5` (dev) / `10` (prod) | No | HikariCP minimum idle connections |
| `DB_CONNECTION_TIMEOUT` | `30000` | No | Connection timeout (ms) |
| `DB_IDLE_TIMEOUT` | `600000` | No | Idle connection timeout (ms) |
| `DB_MAX_LIFETIME` | `1800000` | No | Max connection lifetime (ms) |

### Kafka

| Variable | Default | Required | Description |
|---|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Yes | Kafka bootstrap servers |
| `KAFKA_CONCURRENCY` | `3` (dev) / `5` (prod) | No | Consumer listener concurrency |
| `KAFKA_PARTITIONS` | `25` | No | Default partition count for topics |
| `KAFKA_RETRY_MAX_ATTEMPTS` | `3` (dev) / `5` (prod) | No | Total delivery attempts before DLQ (one delivery plus `max-attempts - 1` retries) |
| `KAFKA_RETRY_BACKOFF_MS` | `1000` (dev) / `2000` (prod) | No | Backoff interval between retries |

### Intelligence

| Variable | Default | Required | Description |
|---|---|---|---|
| `CCE_PARSED_PROTOCOL_CACHE_SIZE` | `256` | No | Max parsed protocol definitions (flattened steps + dependency graph) held in memory |
| `CCE_PROTOCOL_REFRESH_INTERVAL_MS` | `60000` | No | How often to reconcile the in-memory protocol caches against definitions written by the CCE Protocol Service. Upper bound on how long a newly published protocol takes to start matching its condition-only triggers, and on how long a retired one keeps doing so |
| `CCE_PUBLISH_CONFIRM_TIMEOUT_MS` | `5000` | No | How long to wait for Kafka to acknowledge an intelligence trigger before recording the event as unpublished |

### Observability

No environment variables. Metrics are exposed at `/actuator/prometheus` and require no configuration;
there is no distributed-tracing backend on the classpath, so `correlationId` propagation is via MDC and
log output only.

---

## 3. Docker Deployment

### Build the Image

> **Build from the workspace directory, not this repo.** `Dockerfile` copies both repositories, and
> Docker `COPY` cannot reach outside its build context. Building with this repo as the context fails in
> stage 1 with `Included build '/.../cce-common-util' does not exist`.

```bash
# Run from the workspace directory containing both cce-matcher-service and cce-common-util
docker build -f cce-matcher-service/Dockerfile -t cce-matcher-service:2.0.0 .
```

### Run with Docker

```bash
docker run -d \
  --name cce-matcher-service \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_HOST=postgres-host \
  -e DB_PORT=5433 \
  -e DB_NAME=ccedb \
  -e DB_USERNAME=cce_user \
  -e DB_PASSWORD=cce_pass \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka-host:9092 \
  cce-matcher-service:2.0.0
```

### Docker Compose (Local Development)

PostgreSQL, Kafka, and the shared database are deployed by the **CCE Collector Service**. Start the collector infrastructure, then run the matcher service:

```bash
# 1. Start shared infrastructure (PostgreSQL on port 5433 + Kafka on port 9092)
cd /path/to/cce-collector-service
docker compose up -d

# 2. Run the matcher service (Flyway applies schema migrations automatically)
cd /path/to/cce-matcher-service
./gradlew bootRun
```

The shared infrastructure (from [cce-collector-service deployment guide](https://github.com/Jayaprakash8887/cce-collector-service/blob/release-1.0.0/docs/deployment-guide.md)) provides:
- **PostgreSQL 16** on port `5433` (user: `cce_user`, password: `cce_pass`, database: `ccedb`)
- **Apache Kafka 3.7.0 KRaft** on port `9092` (single broker, no Zookeeper)

> **Note:** All CCE services share the same `ccedb` database. Each service owns its own tables — Flyway migrations are namespaced to avoid conflicts.

---

## 4. Kubernetes Deployment

### Example Deployment Manifest

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cce-matcher-service
  labels:
    app: cce-matcher-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: cce-matcher-service
  template:
    metadata:
      labels:
        app: cce-matcher-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: "8080"
    spec:
      containers:
        - name: cce-matcher-service
          image: cce-matcher-service:2.0.0
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: SERVER_PORT
              value: "8080"
            - name: DB_HOST
              valueFrom:
                configMapKeyRef:
                  name: cce-config
                  key: db-host
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: cce-secrets
                  key: db-password
            - name: KAFKA_BOOTSTRAP_SERVERS
              valueFrom:
                configMapKeyRef:
                  name: cce-config
                  key: kafka-bootstrap-servers
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 5
          resources:
            requests:
              cpu: "500m"
              memory: "512Mi"
            limits:
              cpu: "2000m"
              memory: "2Gi"
```

---

## 5. Database Setup

All CCE services share the same PostgreSQL database (`ccedb`) deployed by the [CCE Collector Service](https://github.com/Jayaprakash8887/cce-collector-service/blob/release-1.0.0/docs/deployment-guide.md). Each service owns its own tables within the shared database — Flyway migrations are namespaced to avoid conflicts.

### Shared Infrastructure

| Property | Value |
|---|---|
| **PostgreSQL Host** | Same as collector service |
| **PostgreSQL Port** | `5433` (local dev) / as configured (production) |
| **Shared User** | `cce_user` |
| **Shared Database** | `ccedb` |

### No Separate Database Creation Needed

The database and user are created by the collector service's Docker Compose. The matcher service only needs to run its Flyway migrations, which happen automatically on startup.

> **Matcher service tables:** `protocol_definition`, `protocol_instance`, `step_instance`, `deviation`, `trigger_index`, `matcher_event_log`, `action_definition`, `intelligence_event_log`, `facility`

### Schema Migrations

Flyway manages all schema migrations automatically on application startup.

- Migrations are located at `classpath:db/migration`
- Migration history is tracked in a namespaced Flyway table, `flyway_schema_history_matcher` (not the default `flyway_schema_history`), so it does not collide with other CCE services' migration history in the shared `ccedb` database
- `V1__initial_schema.sql` creates all 12 tables with their indexes and constraints
- `ddl-auto=validate` ensures Hibernate validates entity mappings against the actual schema
- **Production:** set `spring.flyway.baseline-on-migrate=false` (default in the prod profile)

> **First deployment into a shared `ccedb`.** The service owns the tables `protocol_definition`,
> `protocol_instance`, `step_instance`, `deviation`, `trigger_index`, `matcher_event_log`,
> `action_definition`, `intelligence_event_log`, `facility`,
> `protocol_instance_history` and `step_instance_history`. `V1` creates them, so those names must
> be free in the target database — the service cannot share them with another tenant of `ccedb`.

**Post-deploy check:**

```sql
-- Expect one row, version 1, success = true
SELECT version, description, success FROM flyway_schema_history_matcher ORDER BY installed_rank;
```

### Connection Pool Sizing

The HikariCP pool size should account for:
- Kafka consumer threads (concurrency setting, default 5 in prod)
- Tomcat thread pool (default 200, but only `/actuator` traffic — effectively idle)
- Async audit service threads

**Formula:** `maximumPoolSize ≥ (kafka_concurrency × 2) + 15`

With default production settings (concurrency=5): `30` connections is appropriate.

---

## 6. Kafka Setup

The CCE Matcher Service shares the Kafka cluster deployed by the [CCE Collector Service](https://github.com/Jayaprakash8887/cce-collector-service/blob/release-1.0.0/docs/deployment-guide.md). The collector publishes to `cce.events.inbound`, which this service consumes.

### Topics

The service auto-creates topics on startup via `KafkaAdmin` + `NewTopic` beans. For production, pre-create topics with appropriate replication:

```bash
# Primary topics
kafka-topics.sh --create --topic cce.events.inbound \
  --partitions 25 --replication-factor 3 --bootstrap-server kafka:9092

kafka-topics.sh --create --topic cce.intelligence.triggers \
  --partitions 25 --replication-factor 3 --bootstrap-server kafka:9092

# DLQ topics
kafka-topics.sh --create --topic cce.events.inbound.dlq \
  --partitions 25 --replication-factor 3 --bootstrap-server kafka:9092

```

### Consumer Group

- **Group ID:** `cce-matcher-service`
- **Auto offset reset:** `earliest`
- **Isolation level:** `read_committed`
- **Max poll records:** 200 (prod)

### Error Handling

Failed messages are retried with `FixedBackOff` (5 attempts × 2s interval in prod), then routed to the corresponding `.dlq` topic. Monitor DLQ topics for persistent failures.

---

## 7. JVM Tuning

The Dockerfile does not set a default `JAVA_OPTS` — its entrypoint is `java $JAVA_OPTS -jar app.jar`, so the JVM runs with no extra flags unless `JAVA_OPTS` is supplied at runtime (`docker run -e JAVA_OPTS=...` or a Kubernetes env var).

### Recommended Production Flags

```bash
JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -Djava.security.egd=file:/dev/./urandom"
```

| Flag | Purpose |
|---|---|
| `UseContainerSupport` | Respect container memory/CPU limits |
| `MaxRAMPercentage=75.0` | Use 75% of container memory for heap |
| `UseG1GC` | G1 garbage collector (recommended for services) |
| `MaxGCPauseMillis=200` | Target max GC pause time |
| `UseStringDeduplication` | Reduce memory for duplicate strings (FHIR payloads) |

---

## 8. Health Checks & Monitoring

### Health Endpoints

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Overall application health |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/health/readiness` | Kubernetes readiness probe |
| `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `/actuator/info` | Application info |
| `/actuator/metrics` | Micrometer metrics browser |

### Key Metrics to Monitor

| Metric | Type | Alert Threshold |
|---|---|---|
| `cce.events.processed` | Counter | Sudden drop = consumer issue |
| `cce.events.duplicate` | Counter | High rate = upstream replay |
| `cce.events.matched{status="zero_match"}` | Counter (tagged) | High rate = missing protocols. There is no separate `cce.events.zero_match` metric — it is a tag on `cce.events.matched` |
| `cce.events.processing.duration` | Timer | p99 > 500ms |
| `cce.step.matching.duration` | Timer | p99 > 200ms |
| `cce.protocol.instances.active` | Gauge | Abnormal growth |
| `hikaricp.connections.active` | Gauge | Near pool max |
| `kafka.consumer.fetch.manager.records.lag` | Gauge | Growing lag |

### Prometheus Scrape Configuration

```yaml
scrape_configs:
  - job_name: 'cce-matcher-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['cce-matcher-service:8080']
        labels:
          application: 'cce-matcher-service'
```

### Recommended Grafana Dashboards

- **Spring Boot Statistics** — JVM, HTTP, HikariCP, Tomcat metrics
- **Kafka Consumer Lag** — Consumer group lag monitoring
- **Custom CCE Dashboard** — Event processing rates, matching durations, active instances

---

## 9. Backup & Recovery

### Database Backup

```bash
# Full backup
pg_dump -U cce_user -h postgres-host -p 5433 ccedb > backup_$(date +%Y%m%d).sql

# Restore
psql -U cce_user -h postgres-host -p 5433 ccedb < backup_20250101.sql
```

### Recovery Considerations

- **Kafka offsets:** Consumer group offsets are stored in Kafka. On restart, processing resumes from the last committed offset.
- **Idempotency:** The `(cloudeventsId, source)` unique constraint on `matcher_event_log` ensures safe event reprocessing.
- **Protocol definitions:** Stored in PostgreSQL with JSONB and owned by the CCE Protocol Service, which also maintains `trigger_index`. Matcher reads both; it cannot rebuild the index. Its in-memory caches reconcile against the tables every `CCE_PROTOCOL_REFRESH_INTERVAL_MS`, so a restart is never needed to pick up a protocol change.
- **Condition-only triggers:** Held in memory only — they have no `data[]` to index. Derived from the stored definitions at startup and re-derived on every reconciliation sweep, so a protocol published or retired by the management service takes effect within one `CCE_PROTOCOL_REFRESH_INTERVAL_MS`.

---

## 10. Troubleshooting

### Common Issues

| Problem | Possible Cause | Resolution |
|---|---|---|
| Service won't start | Database unreachable | Check `DB_HOST`, `DB_PORT`, network connectivity |
| Flyway migration fails | Schema already exists | V1 is greenfield-only: `baseline-version` is `0`, so Flyway baselines at 0 and still applies V1, which fails on existing tables. Confirm the target `ccedb` is empty |
| No events processed | Kafka unreachable | Check `KAFKA_BOOTSTRAP_SERVERS`, broker health |
| Events going to DLQ | Deserialization errors | Check message format matches CloudEvents schema |
| High consumer lag | Slow processing | Increase `KAFKA_CONCURRENCY`, check DB performance |
| Connection pool exhaustion | Too many concurrent requests | Increase `DB_POOL_SIZE` |

### Log Configuration

Production logging levels (in `application-prod.yml`):
```yaml
logging:
  level:
    root: WARN
    org.openphc.cce.matcher: INFO
    org.apache.kafka: ERROR
    org.hibernate: ERROR
```

To enable debug logging for specific components temporarily:
```bash
-Dlogging.level.org.openphc.cce.matcher.service.MatcherEngine=DEBUG
```
