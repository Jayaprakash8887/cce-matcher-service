# Flow Diagrams

This document provides detailed sequence and flow diagrams for all major workflows in the CCE Matcher Service.

---

## 1. Inbound Clinical Event Processing (End-to-End)

This is the primary workflow — processing a clinical event from Kafka through the entire matcher engine pipeline.

```mermaid
sequenceDiagram
    autonumber
    participant EHR as CCE Collector Service
    participant Kafka as Apache Kafka
    participant Consumer as InboundEventConsumer
    participant FacilityRef as FacilityService
    participant Engine as MatcherEngine
    participant EventLog as MatcherEventLogService
    participant TriggerMatch as TriggerMatchingService
    participant Parser as PlanDefinitionParser
    participant ExprEval as ExpressionEvaluationService<br/>(JSONLogic + FHIRPath)
    participant ProtoInst as ProtocolInstanceService
    participant StepInst as StepInstanceService
    participant Intel as IntelligenceActionEvaluator
    participant DB as PostgreSQL

    EHR->>Kafka: Publish clinical event<br/>(CloudEvents v1.0)
    Kafka->>Consumer: Poll cce.events.inbound
    Consumer->>Consumer: Set MDC correlationId

    rect rgb(235, 245, 235)
        Note over Consumer,DB: Facility Registration (best-effort, non-fatal)
        Consumer->>FacilityRef: upsertFacility(cloudEvent)
        FacilityRef->>DB: SELECT facility by facility_id
        alt Facility not yet known
            FacilityRef->>DB: INSERT INTO facility
        else Facility name changed
            FacilityRef->>DB: UPDATE facility SET facility_name
        end
        Note over Consumer,FacilityRef: Failure is swallowed — matcher<br/>processing continues regardless
    end

    Consumer->>Engine: processInboundEvent(cloudEvent)

    rect rgb(240, 248, 255)
        Note over Engine,DB: Step 1 — Idempotency Check
        Engine->>EventLog: isDuplicate(cloudeventsId, source)
        EventLog->>DB: SELECT EXISTS(cloudeventsId, source)
        DB-->>EventLog: true/false
        EventLog-->>Engine: isDuplicate result
    end

    alt Duplicate Event
        Engine-->>Consumer: Skip (increment duplicate counter)
    else New Event
        rect rgb(245, 255, 245)
            Note over Engine,DB: Step 2 — Record Event
            Engine->>EventLog: recordEvent(cloudEvent, ZERO_MATCH)
            EventLog->>DB: INSERT INTO matcher_event_log
            DB-->>EventLog: MatcherEventLog entity
            EventLog-->>Engine: eventLog
        end

        rect rgb(255, 248, 240)
            Note over Engine: Step 3 — Extract Resource Info
            Engine->>Engine: extractResourceType(data)
            Engine->>Engine: extractCodes(data)
            Note over Engine: Extracts codes from code, type,<br/>category, clinicalStatus, identifier fields
        end

        rect rgb(248, 240, 255)
            Note over Engine,DB: Step 4 — Tier 1 Structural Match
            Engine->>TriggerMatch: findStructuralMatches(resourceType, codes)
            TriggerMatch->>DB: SELECT FROM trigger_index<br/>WHERE resource_type AND code (GROUP BY + HAVING)
            DB-->>TriggerMatch: List<MatchedStep>
            TriggerMatch-->>Engine: structural matches
        end

        rect rgb(255, 245, 245)
            Note over Engine,ExprEval: Step 5 — Tier 2 Condition Evaluation
            loop For each structural match
                Engine->>Parser: extractSteps(planDefinition)<br/>(cached per protocolDefinitionId for this event)
                Parser-->>Engine: List<StepMetadata>
                alt Action's trigger has no condition
                    Engine->>Engine: Accept match as-is
                else Trigger has a condition
                    Engine->>ExprEval: evaluate(language, expression, eventData)
                    ExprEval-->>Engine: boolean
                end
            end
            Note over Engine,ExprEval: Condition-only triggers (no data[], scenario F3)<br/>are also evaluated here for every inbound event
            Engine->>TriggerMatch: getConditionOnlyTriggers()
            TriggerMatch-->>Engine: List<ConditionOnlyTrigger>
            loop For each condition-only trigger
                Engine->>ExprEval: evaluate(language, expression, eventData)
                ExprEval-->>Engine: boolean
            end
        end

        rect rgb(240, 255, 240)
            Note over Engine,Intel: Step 6 — Process Result
            alt One or more matches
                loop For each matched (protocolDefinitionId, actionId)
                    Engine->>ProtoInst: enrollPatient(patientId, protocolDef, occurredAt)
                    Note over ProtoInst: Idempotent — returns the existing ACTIVE<br/>instance if the patient is already enrolled
                    ProtoInst->>DB: Find or create ProtocolInstance
                    DB-->>ProtoInst: ProtocolInstance
                    ProtoInst-->>Engine: protocolInstance

                    Engine->>Engine: resolveOccurredAt(event)<br/>(clinical time from payload → envelope time → now)
                    Engine->>StepInst: findActionableStep(protocolInstanceId, actionId)
                    alt No actionable step exists yet
                        Engine->>StepInst: createStep(protocol, actionId, 0, now, ...)
                        StepInst->>DB: INSERT INTO step_instance (step_status=NOT_STARTED, sla_status=NULL)
                    end

                    Engine->>StepInst: completeStep(step, eventLogId, source, occurredAt)
                    Note over StepInst: Single call — internally sets completed_at (clinical time,<br/>clamped to now), detects order violations, runs progressive<br/>instantiation of mandatory dependents (see §9), backfills<br/>unrecorded mandatory predecessors (see §9). Does NOT set<br/>sla_status — timeliness is the Compliance Service's call
                    StepInst->>DB: UPDATE step_instance SET step_status=COMPLETED,<br/>completed_at, matched_event_id

                    Engine->>Intel: evaluateOnCompletion(step, eventPayload)

                end
                Engine->>EventLog: updateStatus(eventLog, MATCHED)
            else No Matches
                Note over Engine,EventLog: eventLog was already recorded with ZERO_MATCH<br/>in Step 2 — no further status update needed
            end
        end
    end

    Consumer->>Kafka: Acknowledge offset
```

## 2. Protocol Read Model & Cache Reconciliation

Matcher does not load protocols. `protocol_definition`, `trigger_index` and `action_definition` are
written by the CCE Protocol Service; Matcher only reads them.

Tier 1 reads `trigger_index` per event, so it needs no warm-up. Condition-only triggers have no
`data[]` to index and live only in memory, so they are derived from the stored definitions — at startup
and then on a timer.

```mermaid
sequenceDiagram
    autonumber
    participant Mgmt as CCE Protocol<br/>Service
    participant DB as PostgreSQL
    participant Timer as Startup + refresh timer
    participant PDS as ProtocolDefinitionService
    participant Parser as PlanDefinitionParser
    participant TMS as TriggerMatchingService

    Mgmt->>DB: INSERT protocol_definition + trigger_index
    Note over Mgmt,DB: Owned entirely by the management service

    Timer->>PDS: refreshProtocolCaches()
    PDS->>DB: findFingerprintsByStatus(ACTIVE)
    DB-->>PDS: (id, updated_at) only — no JSONB
    PDS->>PDS: drop ids no longer ACTIVE
    loop per new or changed definition
        PDS->>DB: findById(id)
        DB-->>PDS: full definition
        PDS->>Parser: parse(definition) + extractConditionOnlyTriggers
        alt Parses
            Parser-->>PDS: condition-only triggers
            PDS->>TMS: registerConditionOnlyTriggers(id, triggers)
        else Unparseable
            Parser-->>PDS: throws
            PDS->>PDS: log ERROR and skip<br/>(one bad row must not starve the rest)
        end
    end
```

Because Matcher never writes these rows, it has no local mutation to invalidate on — so the same
reconciliation runs on a timer (`cce.protocol.refresh-interval-ms`, default 60s), converging all three
cases within one interval:

| Change by the management service | Tier 1 (`trigger_index`) | Condition-only + parsed caches |
|---|---|---|
| Protocol published | Immediate (read per event) | Registered on next refresh |
| Protocol retired / deleted | Immediate (rows gone) | Dropped on next refresh |
| Definition modified in place | Immediate | Re-derived on next refresh, detected via `updated_at` |

A refresh that finds nothing changed reads only `(id, updated_at)` — a definition's JSONB is fetched and
re-parsed only when its stamp moves or its id is new. Each instance polls independently; the caches are
per-process, so no leasing is needed.

> **Note:** in-place modification is detected through `updated_at`, so a writer that edits `definition`
> without advancing that column will not be picked up. Publishing and retiring are detected regardless,
> since those change which ids are `ACTIVE`.
>
> **Possible refinement:** a `protocol.published` Kafka event would cut the window from one interval to
> near-zero. It would supplement this sweep rather than replace it — an event that is missed while a
> consumer is down would otherwise leave the cache stale indefinitely, so the periodic reconciliation
> stays as the backstop.

## 3. Time-Driven SLA Transitions (owned elsewhere)

Matcher schedules these but does not apply them. When it creates a step it writes one
`step_sla_state_transition` row per threshold; the CCE Compliance Service claims a row once its deadline
has passed — or as soon as the step completes, since `completed_at` then decides the outcome on its own —
applies the `sla_status` change, and records the `OVERDUE` / `MISSED` deviation. There is no Kafka hop —
the two services meet on the table, with one writer per column. See
[Architecture Overview §1.1](architecture-overview.md#11-sla-transition-evaluation-contract).

```mermaid
sequenceDiagram
    participant Matcher as CCE Matcher Service
    participant DB as PostgreSQL (shared)
    participant Eval as CCE Compliance Service

    Matcher->>DB: createStep + INSERT step_sla_state_transition (same tx)
    Note over Matcher,DB: One row per threshold present —<br/>a step with no tolerance-days gets no MISSED row

    loop Polling interval
        Eval->>DB: Claim rows WHERE is_processed = FALSE<br/>AND next_attempt_at <= now() FOR UPDATE SKIP LOCKED
        Eval->>DB: Read step_status / completed_at to judge the crossing
        alt Step NOT_STARTED
            Eval->>DB: UPDATE sla_status + INSERT deviation
        else Step COMPLETED past process_by
            Eval->>DB: INSERT deviation only<br/>(completion already settled sla_status)
        else Step COMPLETED before process_by
            Eval->>DB: Consume the row — nothing breached
        end
        Eval->>DB: UPDATE …SET is_processed = true (same tx)
    end
```

## 4. Protocol Enrollment Flow

```mermaid
flowchart TD
    A["Inbound Event<br/>Matched to Protocol Definition Action"] --> B{"Patient has<br/>active protocol?"}

    B -->|"Yes"| C["Return existing<br/>ProtocolInstance"]
    B -->|"No"| D["Create new<br/>ProtocolInstance"]

    D --> E["Set status = ACTIVE"]
    E --> F["Set enrolledAt = clinical occurrence time<br/>(resolveOccurredAt: payload → envelope → now)"]
    F --> G["Link to ProtocolDefinitionEntity"]
    G --> H["Set protocolCanonical = url|version"]

    C --> I["Check for existing<br/>active step"]
    H --> I

    I -->|"Active step exists<br/>for this actionId"| J["Use existing<br/>StepInstance"]
    I -->|"No active step"| K["Create new<br/>StepInstance"]

    K --> L["Calculate repeatIndex"]
    L --> N["Create StepInstance — step_status = NOT_STARTED,<br/>sla_status = NULL (nothing judged yet)<br/>+ schedule its SLA transition rows (see §3)"]

    J --> P["completeStep()"]
    N --> P

    P --> SS["step_status = COMPLETED<br/>(the event arrived — always set)"]
    SS --> Q{"Settle sla_status against the<br/>scheduled thresholds<br/>(completedAt = clinical occurrence time)"}

    Q -->|"No due threshold<br/>— nothing to breach"| MET["sla_status = MET"]
    Q -->|"completedAt &lt; dueDate<br/>— beat the deadline"| MET
    Q -->|"dueDate ≤ completedAt &lt; missedDate<br/>— recorded late"| OD["sla_status = OVERDUE"]
    Q -->|"completedAt ≥ missedDate<br/>— recorded after being written off"| MI["sla_status = MISSED"]

    MET --> W["Set completedAt, completedBySource,<br/>matchedEventId"]
    OD --> W
    MI --> W
    W --> X["Record the transition in step_instance_history"]
```

**The two statuses are independent.** `step_status` answers *did the work happen?* and is always
`COMPLETED` here. `sla_status` answers *was it on time?* and is one of the three outcomes above — so a
row can legitimately read `COMPLETED` + `MISSED`, meaning the work was done but only after the step had
been written off.

> There is no `EARLY` or `ON_TIME` status. The 1.x schema had a separate `completion_status` column with
> `EARLY`/`ON_TIME`/`LATE`; `V2` drops it, because the pair above already expresses it —
> `COMPLETED`+`MET` is on time, `COMPLETED`+`OVERDUE`/`MISSED` is late. See
> [`SlaStatus`](data-dictionary.md#slastatus) for the full value reference.

## 5. Deviation Detection & Recording

Matcher records exactly one deviation type: `ORDER_VIOLATION`, detected on completion. The time-driven
`OVERDUE` and `MISSED` deviations are recorded by the CCE Compliance Service (§3), which also owns
intelligence evaluation for them.

> **Intelligence action evaluation** is triggered after the deviation is recorded, and only when it was
> newly inserted. See §6 for the full intelligence pipeline flow.

```mermaid
flowchart TD
    T2["completeStep(): a must-predecessor is still outstanding<br/>(detectOrderViolations)"]
    T2 -->|"type=ORDER_VIOLATION<br/>+ incompletePrerequisites metadata"| RD

    RD["DeviationService.createDeviation()"]
    RD --> DX{"Deviation of this type<br/>already exists for step?"}
    DX -->|"Yes (redelivery / concurrent)"| DXR["Return DeviationResult(existing, created=false)<br/>— no insert, caller skips intelligence eval"]
    DX -->|"No"| D1["Build Deviation: type, detectedAt = now(),<br/>metadata from the caller,<br/>linked to the StepInstance (the enrolment is reached through it)"]
    D1 --> D6["Persist to DB"]
    D6 --> D8["Caller evaluates intelligence actions<br/>(IntelligenceActionEvaluator), created=true only"]
```

## 6. Intelligence Action Evaluation & Trigger Publishing

This flow is triggered after an `ORDER_VIOLATION` is detected or after a step is completed. (`OVERDUE`/`MISSED` deviations are raised by the CCE Compliance Service, which owns intelligence evaluation for them.) The `IntelligenceActionEvaluator` evaluates PlanDefinition intelligence action conditions and publishes intelligence events.

```mermaid
sequenceDiagram
    autonumber
    participant Trigger as Deviation Detection /<br/>Step Completion
    participant Evaluator as IntelligenceActionEvaluator
    participant Parser as PlanDefinitionParser
    participant ExprEval as ExpressionEvaluationService
    participant ActionDefSvc as ActionDefinitionService
    participant Producer as IntelligenceTriggerProducer
    participant Kafka as Apache Kafka
    participant DB as PostgreSQL

    Trigger->>Evaluator: evaluateOnDeviation(step, deviation)<br/>or evaluateOnCompletion(step, eventPayload)

    rect rgb(240, 248, 255)
        Note over Evaluator,Parser: Step 1 — Resolve plan definition & find intelligence actions
        Evaluator->>Parser: parsedProtocolCache.get(protocolDef)
        Note over Evaluator,Parser: Shared cache of flattened steps + dependency graph,<br/>keyed by protocolDefinitionId (see §2)
        Parser-->>Evaluator: ParsedProtocol
        Evaluator->>Evaluator: ParsedProtocol.step(step.actionId),<br/>read its intelligenceActions()
    end

    rect rgb(245, 255, 245)
        Note over Evaluator,ExprEval: Step 2 — Build context & evaluate conditions
        Evaluator->>Evaluator: Build runtime context<br/>(stepStatus, slaStatus, actionId, repeatIndex, dueDate)<br/>plus deviationType/daysOverdue on the deviation path

        loop For each intelligence action
            Evaluator->>ExprEval: evaluate(action.language,<br/>action.expression, context)
            ExprEval-->>Evaluator: boolean

            alt Condition is true
                rect rgb(255, 248, 240)
                    Note over Evaluator,Kafka: Step 3 — Resolve, record, publish
                    Evaluator->>ActionDefSvc: resolveByCanonical(action.definitionCanonical)
                    ActionDefSvc->>DB: SELECT FROM action_definition
                    DB-->>ActionDefSvc: ActionDefinition

                    alt ActionDefinition not found
                        ActionDefSvc-->>Evaluator: null
                        Evaluator->>Evaluator: Log warning, skip action
                    else ActionDefinition found
                        ActionDefSvc-->>Evaluator: ActionDefinition
                        Evaluator->>DB: INSERT IntelligenceEventLog<br/>(published=false,<br/>eventPayload, triggerReason,<br/>stepActionId, evaluationExpression,<br/>evaluationContext)
                        DB-->>Evaluator: IntelligenceEventLog

                        Evaluator->>Evaluator: Build IntelligenceTriggerEvent
                        Evaluator->>Producer: publishAndConfirm(event)
                        Producer->>Kafka: Send to cce.intelligence.triggers<br/>(key: intelligenceEventId)
                        Kafka-->>Producer: Ack (or timeout)

                        Evaluator->>DB: UPDATE IntelligenceEventLog<br/>(published=true, publishedAt=now())<br/>only if the broker acknowledged
                        Evaluator->>DB: UPDATE deviation<br/>(intelligenceEventId=UUID)
                    end
                end
            else Condition is false
                Note over Evaluator: Skip action
            end
        end
    end

    Evaluator-->>Trigger: List<IntelligenceEventLog>
```

### Intelligence Event Content Assembly

`IntelligenceTriggerEvent` is a flat domain message — every field below is a real field on it. The
richer per-execution context (deviation id, evaluation expression, evaluated context) is written to
`intelligence_event_log`, not onto the event.

```mermaid
flowchart TD
    subgraph "Input Sources"
        STEP["StepInstance<br/>(stepStatus, slaStatus, actionId)"]
        PI["ProtocolInstance<br/>(patientId, protocolCanonical, protocolDefinitionId)"]
        RULE["IntelligenceActionInfo<br/>(severity, intelligenceDestination)"]
        ACTDEF["ActionDefinition<br/>(id, actionType)"]
        EVT["Inbound CloudEvent<br/>(data)"]
        LOG["IntelligenceEventLog<br/>(id, assigned on insert)"]
    end

    subgraph "IntelligenceTriggerEvent"
        E_ID["id: UUID (new)"]
        E_IEID["intelligenceEventId"]
        E_SUBJECT["subject: patientId"]
        E_ADID["actionDefinitionId"]
        E_PDID["protocolDefinitionId"]
        E_AT["actionType"]
        E_SEV["severity"]
        E_DEST["intelligenceDestination"]
        E_STS["stepStatus"]
        E_SS["slaStatus"]
        E_AID["actionId"]
        E_PC["protocolCanonical"]
        E_DAT["detectedAt"]
        E_PAY["eventPayload"]
    end

    STEP --> E_STS
    STEP --> E_SS
    STEP --> E_AID
    PI --> E_SUBJECT
    PI --> E_PC
    PI --> E_PDID
    RULE --> E_SEV
    RULE --> E_DEST
    ACTDEF --> E_ADID
    ACTDEF --> E_AT
    EVT --> E_PAY
    LOG --> E_IEID
```

> `intelligenceEventId` is the `intelligence_event_log` row id, filled in after the row is inserted and
> used for cross-service correlation. It is also the Kafka message key.

## 7. Kafka Consumer Error Handling

```mermaid
flowchart TD
    A["Kafka delivers message"] --> B["Consumer receives message"]
    B --> C{"Deserialization OK?"}
    C -->|"No"| D["ErrorHandlingDeserializer<br/>wraps error"]
    D --> D2["Route to DLQ"]

    C -->|"Yes"| F["Set MDC correlationId"]
    F --> G["Delegate to service"]
    G --> H{"Processing OK?"}
    H -->|"Yes"| I["Acknowledge offset"]
    H -->|"No"| J["Increment error counter"]
    J --> K{"Retries remaining?<br/>(default: 3)"}
    K -->|"Yes"| L["Wait backoff (1s)"]
    L --> G
    K -->|"No"| M["Publish to &lt;topic&gt;.dlq"]
    M --> N["Acknowledge original offset"]
    N --> O["Log DLQ routing"]
```

## 8. Flat Sub-Step Processing

All steps (including those originally nested in `action.action[]`) are treated as peers. Nested step-type actions are flattened at parse time with `relatedSteps` linking them to their parent. No separate sub-step routing is needed — the standard matching and completion flow handles them uniformly.

```mermaid
flowchart TD
    MATCH["Tier 1/2 match returns actionId"] --> NORMAL["Standard step processing<br/>(Section 1, Step 6)"]
    NORMAL --> COMPLETE["completeStep(step)"]
    COMPLETE --> DEPS["createDependentSteps()<br/>(find steps declaring this step id as their prerequisite)"]
    DEPS --> CREATED["Create dependent steps (NOT_STARTED, sla NULL)"]
    CREATED --> BACKFILL["backfillMissingMandatorySteps()<br/>(see 'Unrecorded Mandatory Predecessor Backfill' below)"]
```

### Dependent Step Creation on Completion

`relatedAction` is a **relative** pointer — a step states how it sits against another, from either end (`after-*` or `before-*`) — so the forward direction needed here comes from the normalized graph built by `PlanDefinitionParser.buildDependencyGraph()`. When any step completes, `createDependentSteps()` looks up the steps that depend on it and creates them with due dates taken from the offset on the edge between them.

```mermaid
flowchart TD
    START["createDependentSteps(completedStep, graph)"] --> FIND["graph.successorsOf(completedStep):<br/>steps ordered after it by either an after-* edge<br/>of their own or a before-* edge on the completed step"]
    FIND --> LOOP{"For each dependent step"}
    LOOP --> DEDUP{"An instance for this step<br/>already exists?"}
    DEDUP -->|"Yes"| SKIP["Skip — avoid duplicate<br/>(already created reactively via its own<br/>trigger, or by a redelivered predecessor)"]
    DEDUP -->|"No"| MUST{"dependent step's<br/>requiredBehavior == must?"}
    MUST -->|"No (could / unspecified)"| SKIP2["Skip pre-creation — a dangling row could later go<br/>MISSED even though its event never arrives;<br/>created on the fly if its own trigger fires"]
    MUST -->|"Yes"| FANIN{"Any OTHER prerequisite still outstanding<br/>(NOT_STARTED + sla NULL/OVERDUE)?"}
    FANIN -->|"Yes"| SKIP3["Defer — that prerequisite's own completion<br/>re-runs this, so the due date anchors to<br/>the LAST prerequisite to finish"]
    FANIN -->|"No"| CALC["Calculate due date from the dependent step's own<br/>offset + relationship<br/>(after-end → completedAt [clinical time], after-start → dueDate)"]
    CALC --> RECURRING{"TimingInfo.count > 1?"}

    RECURRING -->|"Yes"| MULTI["Create N recurring instances with staggered due dates"]
    RECURRING -->|"No"| SINGLE["createStep(dependent, due, missed) — sla NULL<br/>+ schedules its transition rows"]

    MULTI --> NEXT["Continue to next"]
    SINGLE --> NEXT
    SKIP --> NEXT
    SKIP2 --> NEXT
    SKIP3 --> NEXT
    NEXT --> LOOP
    LOOP -->|"Done"| END["Return"]
```

### Unrecorded Mandatory Predecessor Backfill (backfillMissingMandatorySteps)

Progressive instantiation only works *forward*, so a step created reactively from its own trigger leaves the mandatory steps that should have preceded it with no `step_instance` row — invisible both in the journey view ("not started") and to the SLA evaluator, having no scheduled transition. After every completion, mandatory predecessors of the observed progress that have no row are materialized as `NOT_STARTED` with no SLA judgement. See `architecture-overview.md` §6.1 for the full rationale.

```mermaid
flowchart TD
    START["backfillMissingMandatorySteps(completedStep, allSteps)"] --> OBSERVED["Collect observed step ids<br/>(all step_instance rows of this protocol instance)"]
    OBSERVED --> PRED["computeMustPredecessorSteps():<br/>transitive relatedAction ancestors of every observed step,<br/>restricted to requiredBehavior == must"]
    PRED --> MISSING{"Any with no<br/>step_instance row?"}
    MISSING -->|"No"| DONE["Return — nothing to backfill"]
    MISSING -->|"Yes"| LOOP{"For each missing<br/>mandatory step"}
    LOOP --> DATES["due = completedStep.completed_at (clinical time)<br/>missed = due + tolerance-days (no row if unset)<br/>written as step_sla_state_transition rows"]
    DATES --> CREATE["createStep(stepId, repeatIndex 0) — sla NULL"]
    CREATE --> LOOP
    LOOP -->|"Done"| DONE2["Their transition rows now exist, so Compliance drives<br/>sla NULL → OVERDUE → MISSED (deviation), judging any<br/>late completion against the completed_at Matcher recorded"]
```

> Steps still **ahead** in the chain are deliberately excluded — backfilling them would stamp them with this completion's time and flatten the schedule their own `relatedAction` offsets define. They are left to progressive instantiation.

> **Protocol completion:** there is currently no code path that transitions a `ProtocolInstance` out of `ACTIVE`. The automatic completion check and its supporting graph helpers were removed pending finalized completion criteria — see [Architecture Overview §6.2](architecture-overview.md#62-protocol-instance).
