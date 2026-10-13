# Implementation plan: Step groups

**Status:** Draft for review.
**Based on:** [design-repeating-step-groups.md](design-repeating-step-groups.md) (the design), the decisions in §1, and the points in §2 with their status, as of 2026-10-12. Question ids (A1–E6) are the design's.
**Baseline:** CCE 2.0.0 (`release-2.0.0` of `cce-common-util`, `cce-matcher-service`, `cce-step-sla-service`, `cce-protocol-service`, `cce-data-pipeline`, `cce-insights-service`).

**Approach.** Phase 1 builds the whole data model; later phases add behaviour. Each phase is switched on by lifting a load-time check in the protocol service. Until a phase ships, a PlanDefinition that uses its feature is refused at load, so no feature can be half-active in production. Protocols without groups behave exactly as today throughout.

---

## 1. Decisions

| Id | Decision | What it means for the build |
|---|---|---|
| A1 | (a) Same verdict | A group with a deadline goes `OVERDUE` / `MISSED` whether the patient came and left work undone or never came. The group deviation's metadata lists the member steps that weren't completed. |
| A2 | (b) Verdict only | Reports show the group's `sla_status`. The matcher still records `completed_at` when late work completes a closed group; reports don't show it. |
| A3 | (b) Optional groups allowed | An optional group is never opened in advance. Once one of its steps is matched, its `must` steps become mandatory within it and are created. It never counts towards its parent's completion, started or not (point 11). It can't carry `tolerance-days`. |
| A4 | (b) Approximate | ANC protocols set visit due dates as offsets from a registration step. No code change. |
| B1 | (b) Inner repeats allowed | An inner group may repeat within one occurrence of its outer group. Its cycles are numbered within that occurrence. |
| B2 | (b) Calendar day | For a visit-driven group, the clinical day of an event, in the deployment's time zone (point 4), decides its cycle. |
| B3 | Extension, value-driven | A `cycle-boundary` extension on the group, with a coded value (§2, point 5). |
| B4 | Open on closure | The next scheduled cycle opens when the previous one closes. Its due date follows a fixed calendar (points 1 and 2). |
| B5 | (a) Hand-off row | Step-sla writes a row that the matcher drains. Only the matcher creates steps and schedules deadlines. |
| B6 | (a) Nearest due date | `findActionableStep` picks the open row whose due date is nearest the event's clinical time. |
| C1 | (b) Warn | A step deadline under a group deadline is logged as a warning at load. The group's deadline applies; the step's `tolerance-days` is ignored. |
| C2 | (a) Require deadlines | Every mandatory step under a scheduled repeating group needs a deadline at some level, checked at load. Visit-driven groups are exempt (point 3). |
| C3 | (b) Runbook step | The Event Replay runbook gains a group-specific step (§4.3). |
| D1 | (a) Nullable reference | `step_sla_state_transition`, `deviation` and `intelligence_event_log` gain `step_group_instance_id`. Exactly one of the step and group references is set. |
| D2 | Calculate | Only facts are stored: `opened_at`, `completed_at`, `closed_at`, `sla_status`. |
| D3 | Deferred | Not in this release. Protocol instances stay `ACTIVE`, as in 2.0.0 today. The group completion logic is written so protocol completion can reuse it later. |
| D4 | As proposed | The rule context gains `groupActionId` and `cycleIndex`. |
| D5 | Yes | Group reporting in insights in this release. Scope: point 15. |
| D7 | `step_group_instance` | Table and column names as in the design. |
| E1 | `all` only | `selectionBehavior` absent or `all`; other values rejected at load. |
| E2 | Scenario 1 (b), scenario 2 (b) | A group whose predecessor is a group opens when that predecessor closes. A top-level group with no predecessor opens when one of its steps is first matched. |
| E3 | (b) | Opening a visit's cycle closes the previous visit's cycle, as incomplete. |
| E4 | Yes, on deviations | A group's `fire-event` actions fire on the group's deviations. The matcher evaluates them through a hand-off from step-sla, which also switches on intelligence for step `OVERDUE` / `MISSED` (point 13). |
| E5 | (b) Fill in later | A group opened before its due date can be worked out gets the due date, and its deadline rows, when its predecessor completes. |
| E6 | (a) Require a predecessor | A top-level scheduled repeating group must have a `relatedAction` predecessor, checked at load. |

---

## 2. Points the decisions left open

The decisions in §1 left the cases below open. Each point is labelled with its status:
- ***Decided:*** settled by an explicit decision. Points 1–5, 11 and 13.
- ***Accepted proposal:*** a detail whose proposal stands unless a reviewer objects. Points 6–10, 12, 16 and 17.
- ***Open:*** no answer yet. Point 15.

Point 14 fell away when protocol completion was deferred. Each point names the phase it affects.

**1. When is the next scheduled cycle due?** *(B4; blocks phase 4)*
B4's proposal settled when the next cycle opens, but not its due date. Example: quarterly review, cycle 0 due 10 Jan, its work finished on 25 Feb. Cycle 1 is due either 10 Apr (fixed calendar) or 25 May (3 months after the work).
*Decided:* fixed calendar, the rule single-step repeats already follow.

**2. A scheduled cycle with nothing to count from.** *(E6 + E5 + B4; blocks phase 4)*
E6 requires a predecessor so that cycle 0 has a due date. But a group still opens early if one of its steps is matched before the predecessor completes. Its due date then waits for E5's fill-in, and if the predecessor never completes, it never gets one. Example: a quarterly review due "1 week after diagnosis". The patient's first vitals arrive on 10 Jan, and the diagnosis is never recorded. When cycle 0 closes, the fixed calendar has nothing to count cycle 1 from, and `boundsDuration` has no start.
*Decided:* when cycle 0 has no due date, count from its `opened_at`. A starting point for counting is not a deadline, so cycle 0 still isn't judged late.

**3. C2 would reject the Rwanda journey.** *(C2 + E3; blocks phase 5)*
C2 requires a deadline on every mandatory step under a repeating group. The Rwanda journey is a repeating group with no deadlines at all; its cycles close through E3 instead. Applied to every repeating group, C2 would refuse that protocol.
*Decided:* apply C2 to scheduled repeats only. A visit-driven cycle closes when the next one opens (E3), so it doesn't need deadlines to close.

**4. Calendar day in which time zone?** *(B2; blocks phase 5)*
Event times are stored in UTC. A check-in at 23:30 UTC on 3 Mar is 01:30 on 4 Mar in Kigali, so the time zone decides which visit it belongs to.
*Decided:* a deployment setting naming the zone (`Africa/Kigali` for Rwanda, `Africa/Nairobi` for Kenya), used by every calendar-day group in that deployment.

**5. A repeating inner group can't be separated by calendar day.** *(B1 + B2 + B3; blocks phase 5)*
Two Consultations on one visit day fall on the same calendar day, so calendar days can't tell them apart. Each repeating group therefore needs its own rule for what starts a cycle. Putting that rule in the PlanDefinition, as a value, is also the answer to B3's "value-driven".
*Decided:* one extension on the group, with a coded value:
- `calendar-day`: a new cycle for each clinical day on which the group has events. This is the Rwanda journey.
- `entry-step`: a new cycle when one of the group's entry steps is matched and the current cycle's row for that step is already completed. The entry steps are derived from the group's structure, not named: they are its members with no predecessor inside the group. Example: history at 10:00, then examination, is one Consultation; history again at 15:00, when the 10:00 history is already done, opens a second. If a group has two entry steps, say history and vitals, the first match of each joins the same cycle.

```json
"extension": [{
  "url": "http://openphc.org/fhir/StructureDefinition/cycle-boundary",
  "valueCode": "calendar-day"
}]
```

Scheduled repeats keep `timingTiming.repeat`. A group has one or the other, never both. A further rule later, such as one cycle per `Encounter`, or naming the step that starts a cycle, would be a new code, plus the code that handles it.

*Not chosen:* an extension naming the step that starts a cycle (it can't express `calendar-day`, so it conflicts with B2), and a coded value plus an optional named step (more to write and validate, with no current need).

**6. Late events for an earlier day.** *(B2 + E3; blocks phase 5)*
The patient visited on 3 Mar and 17 Mar. Vitals dated 10 Mar arrive on 18 Mar, after the 17 Mar cycle opened. No cycle exists for 10 Mar. Opening one would, under E3, close the still-open 17 Mar cycle. It would also get cycle number 2, after the 17 Mar cycle's 1.
*Accepted proposal:*
- a calendar-day cycle's day is not stored. CCE keeps clinical event time in UTC, and the cycle is opened by the first event of its day, so its day is `opened_at`'s date in the deployment's time zone (point 4). One cycle per day is held by the enrollment lock (§4.1); no index can hold it, since the time zone is a deployment setting;
- `cycle_index` numbers cycles in the order they were opened, not by day;
- opening a cycle closes only open cycles for earlier days, and a cycle opened for a day earlier than an existing one is created already closed;
- an event for a day whose cycle is closed is recorded in that cycle, and creates no follow-on steps there.

**7. The due date of a group opened because its predecessor closed.** *(E2; blocks phase 2)*
ANC visit 3 is due "6 weeks after visit 2". Visit 2 is written off at 22 weeks without completing. Visit 3 opens (E2), but there is no completion to count 6 weeks from.
*Accepted proposal:* count from the predecessor's `closed_at`, here the write-off time. This applies to anything, step or group, that follows a group. Anything that follows a step keeps today's rule: it is created only when the step completes.

**8. E5 fill-in edge cases.** *(E5; blocks phase 2)*
- **The group has already finished.** If the predecessor completes after the group is already complete or closed, a deadline filled in afterwards would judge finished work. *Accepted proposal:* fill in only while the group is open and not complete.
- **Steps created with the group.** Steps created when the group opened take the group's due date. While that is unknown they get none, and so no deadline either, and C2's load check doesn't hold at runtime. *Accepted proposal:* fill in those steps' due dates and deadlines at the same moment. This bends the write-once rule for step due dates as well as group ones.

**9. A deadline on a top-level group with no predecessor can never apply.** *(E2 + E5; blocks phase 2)*
Such a group opens when one of its steps is first matched (E2), so it has no planned due date. With no predecessor, E5 never fills one in, and its `tolerance-days` would never schedule anything.
*Accepted proposal:* reject it at load, as the protocol service already rejects a deadline on an optional step.

**10. Nested groups that both have deadlines.** *(C1; blocks phase 2)*
C1 says the group's deadline wins over a step's. If ANC visit 2 (with a deadline) contains a "Lab tests" group that also has one, which applies?
*Accepted proposal:* the outermost, with a warning at load, so there is one verdict per visit.

**11. Optional groups and completion.** *(A3; blocks phase 1)*
*Decided:*
- an optional group is never opened in advance, only when one of its steps is matched, as optional steps are today;
- it never counts towards its parent's completion or closure, whether it has started or not. Its own completion is tracked on its own. Example: if a "Nutrition counselling" group inside ANC visit 2 starts but its diet assessment isn't done, visit 2 is still complete once its own mandatory steps are.

**12. Row choice details.** *(B6; blocks phase 1)*
*Accepted proposal:*
- two open rows equally near the event: the earlier cycle;
- a row with no due date (point 8): ranked after rows that have one, oldest first;
- for a calendar-day group, the event's day picks the cycle first, and B6 applies within it.

**13. Nothing evaluates intelligence on `OVERDUE` or `MISSED` today.** *(E4; blocks phase 3)*
Group deviations are `OVERDUE` and `MISSED`, recorded by step-sla. Step-sla doesn't evaluate intelligence; its code says "Evaluating and publishing intelligence on a deviation is not part of this service yet". In 2.0.0 only `ORDER_VIOLATION`, recorded by the matcher, fires intelligence. A step's `fire-event` action conditioned on `MISSED` doesn't fire either.
*Decided:*
- **Step-sla** judges group deadlines and records group deviations (phase 2). For each deviation it records, step or group, it writes a hand-off row, the same mechanism as B5.
- **The matcher** drains those rows and evaluates the intelligence actions. Step-sla stays free of Kafka and PlanDefinitions.
- **Steps too:** following E4 (intelligence is driven by deviations, step or group), this also switches on intelligence for step `OVERDUE` / `MISSED`. That changes behaviour for protocols already loaded that have `fire-event` actions on those deviations.
- **Groups fire on deviations only,** not on completion as steps do.

**14. Protocol completion rules.** *(D3)*
No longer needed now: protocol completion is deferred (§1, D3). The three rules (what counts as completed, open-ended groups, re-enrollment after completion) return when it is built.

**15. Which group reports?** *(D5; blocks phase 6)*
*Open.* A minimal first set is proposed, to confirm with product before phase 6:
- per group, by facility and period: occurrences opened, completed, and `MET` / `OVERDUE` / `MISSED`;
- group occurrences shown on the patient timeline.

**16. `intelligence_event_log.step_status` for group events.** *(D1; blocks phase 1)*
The column is `NOT NULL`, and a group has no `step_status`.
*Accepted proposal:* make it nullable.

**17. Events after a scheduled group has stopped.** *(B4; blocks phase 4)*
A quarterly review with `count: 4` has had its four cycles. A fifth set of vitals arrives. Opening a fifth cycle would go past what the protocol asked for.
*Accepted proposal:* the step is recorded, with no group, so the work is kept but no new cycle opens. This is the rule in the design's §7.3.

---

## 3. Phases

| Phase | Delivers | Needs | Repos |
|---|---|---|---|
| 1. Foundation | One-off and nested groups, completion, all schema and pipeline changes, B6 row ordering | A3, B6, D1, D2, D7, E1, E2 (scenario 2); points 11, 12, 16 | common-util, matcher, protocol, pipeline, insights (audit) |
| 2. Closure and group deadlines | Group verdicts, closure, groups opening on a predecessor's closure, the hand-off | A1, B5, C1, E2 (scenario 1), E5; points 7–10 | common-util, matcher, step-sla, protocol |
| 3. Intelligence on deviations | Intelligence on group (and step) `OVERDUE` / `MISSED`; rule context | E4, D4; point 13 | common-util, matcher, step-sla |
| 4. Scheduled repeats | The quarterly review | B4, C2, E6; points 1–3, 17 | common-util, matcher, protocol |
| 5. Visit-driven and nested repeats | The Rwanda journey | B1, B2, B3, E3; points 3–6 | common-util, matcher, protocol |
| 6. Group reporting | Insights reports | A2, D5; point 15 | pipeline, insights, insights-ui |

**Order.** Phase 1 can start now. Phases 2–5 each need phase 1; 3, 4 and 5 also need 2, because closure comes from verdicts. Phases 4 and 5 are independent of each other. Phase 6 needs 2, for verdicts to report. Protocol completion (D3) is not part of this plan.

### Phase 1: Foundation

**Scope.**
- Groups that occur once, nested to any depth, with steps attached to their group's occurrence.
- Completion: `completed_at` is set, and `closed_at` with it, since a complete group is closed.
- Deadlines stay on steps exactly as today.
- All schema and pipeline changes for every phase, except the internal hand-off table (phase 2), so the analytics pipeline changes once.

**`cce-common-util`**
- `PlanDefinitionParser`:
  - **New records and fields:** a `GroupMetadata` record (design §5.1); `StepMetadata.groupId`; `TimingInfo` bounds fields (parsed now, used in phase 4).
  - **Groups kept apart:** `flattenAction` branches on `groupingBehavior: logical-group`, so a group never becomes a `StepMetadata`.
  - **Dependency graph:** it accepts groups as nodes.
  - **Helpers:** a step's group path; a group's mandatory members (A3-aware); its entry members.
- Update the 36 `StepMetadata` constructor calls across `cce-common-util`, `cce-matcher-service` and `cce-protocol-service` (mostly tests).
- `ParsedProtocolCache.ParsedProtocol` gains `groups()` and `group(id)`.
- `validateGroups(PlanDefinition)`:
  - **Permanent checks:** a group has no trigger; contains at least one step, directly or nested; `logical-group` only on `step`-typed actions; `selectionBehavior` absent or `all`; no `tolerance-days` on an optional group.
  - **Phase gates:** reject `timing` on a group, `tolerance-days` on a group, the `cycle-boundary` extension, and `fire-event` children of a group.
- **Entities and repositories:**
  - a new `StepGroupInstance` entity and `StepGroupInstanceRepository`;
  - `StepInstance.stepGroupInstance`;
  - nullable step reference plus a group reference on `StepSlaStateTransition`, `Deviation` and `IntelligenceEventLog`;
  - `StepInstanceRepository`: lookups by occurrence, and the ordered actionable-row query (B6, point 12);
  - `ProtocolInstanceRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`).
- **Shared completion logic,** `GroupCompletion`: decides "complete" over a set of members and reads `selectionBehavior`. Built so protocol completion can reuse it when that is built.

**`cce-matcher-service`**
- **Migration `V8__step_groups.sql`:**

```sql
CREATE TABLE step_group_instance (
    id                       UUID        PRIMARY KEY,             -- app-generated UUID v7
    protocol_instance_id     UUID        NOT NULL REFERENCES protocol_instance(id),
    parent_group_instance_id UUID        REFERENCES step_group_instance(id),
    group_action_id          VARCHAR     NOT NULL,
    cycle_index              INTEGER     NOT NULL DEFAULT 0,
    due_date                 TIMESTAMPTZ,
    sla_status               VARCHAR,                             -- step-sla only
    opened_at                TIMESTAMPTZ NOT NULL,                -- opened_at, completed_at, closed_at:
    completed_at             TIMESTAMPTZ,                         --   clinical event time, in UTC
    closed_at                TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT step_group_instance_cycle_key
        UNIQUE NULLS NOT DISTINCT (protocol_instance_id, group_action_id, parent_group_instance_id, cycle_index)
);
CREATE INDEX idx_step_group_instance_protocol ON step_group_instance (protocol_instance_id);

ALTER TABLE step_instance ADD COLUMN step_group_instance_id UUID REFERENCES step_group_instance(id);
CREATE INDEX idx_step_instance_group ON step_instance (step_group_instance_id)
    WHERE step_group_instance_id IS NOT NULL;

-- D1 (a): the same three changes on step_sla_state_transition and deviation
ALTER TABLE step_sla_state_transition
    ALTER COLUMN step_instance_id DROP NOT NULL,
    ADD COLUMN step_group_instance_id UUID REFERENCES step_group_instance(id),
    ADD CONSTRAINT step_sla_state_transition_one_target
        CHECK (num_nonnulls(step_instance_id, step_group_instance_id) = 1);
CREATE UNIQUE INDEX step_sla_state_transition_group_type_key
    ON step_sla_state_transition (step_group_instance_id, transition_type)
    WHERE step_group_instance_id IS NOT NULL;
-- deviation: as above, with deviation_type

ALTER TABLE intelligence_event_log
    ADD COLUMN step_group_instance_id UUID,
    ALTER COLUMN step_status DROP NOT NULL;                       -- point 16
```

- **New `StepGroupService`:**
  - **Opening an occurrence:** insert the row, idempotently through the unique key, then create its mandatory entry steps and open its entry nested groups. An optional group is never opened in advance (point 11).
  - **After each step completes:** recompute completion for the step's occurrence and its ancestors, set `completed_at` and `closed_at`, and open whatever follows a completed group.
  - **Locking:** both run under the `protocol_instance` lock, taken only when the protocol has groups.
- `StepInstanceService`:
  - `createStep` takes the occurrence;
  - `createDependentSteps` deduplicates within an occurrence when the step and its dependent share a group;
  - a dependent inside a group joins or opens its group's occurrence;
  - a dependent that is itself a group opens an occurrence;
  - "after-start" from a group counts from its `due_date`, or `opened_at` if it has none.
- `MatcherEngine`:
  - `createInitialStep` attaches to, or opens, the occurrence and any unopened ancestors. A top-level group opens here (E2, scenario 2).
  - A match on a group's action id is ignored.
  - `findActionableStep` takes the event's clinical time and orders by B6 and point 12. This also fixes today's arbitrary pick between repeated steps.

**`cce-protocol-service`:** call `validateGroups` with the other load-time checks.

**`cce-data-pipeline`**
- Add `public.step_group_instance` to `table.include.list` in `connectors/debezium-postgres-source.json`, and to the `cce_analytics_pub` publication in `cdc/01-configure-replication.sql`.
- ClickHouse tables and ingestion (`schema/01`, `02`) for the new table and the new columns.
- Review `03`, `06`, `07` and `09`. Step-level counts must exclude group rows in `deviation` and `step_sla_state_transition` once phase 2 writes them.
- Rebuild.

**`cce-insights-service`:** audit every query on `deviation` and `step_sla_state_transition`. Each must read correctly once group rows (null step id) appear in phase 2: inner joins to `step_instance` drop them, while counts over the whole table would include them.

**Tests.**
- **Parser:** groups never appear in `extractSteps`, `mustStepIds` or the step graph; a step nested two groups deep is indexed and matched.
- **Load checks:** every rejection above.
- **Opening:** reactive and progressive opening; entry steps only; deduplication within an occurrence.
- **Completion:** nested groups; an optional group, started or not, never holds its parent back.
- **Ordering:** the B6 example in the design.
- **Concurrency:** two members completing at once set `completed_at` once (integration test).
- **Regression:** all existing suites pass unchanged.

**Done when:** protocols without groups behave exactly as before; one-off and nested groups load, open, attach steps and complete.

### Phase 2: Closure and group deadlines

**Scope.**
- Group deadlines and their verdicts.
- Closure without completion.
- Groups that follow a group opening when it closes.
- E5's fill-in.
- The hand-off from step-sla to the matcher.

**`cce-matcher-service`**
- **Migration `V9`:** an internal hand-off table, `sla_handoff`, with the same claim and retry columns as `step_sla_state_transition`, a nullable `step_group_instance_id`, and a nullable `deviation_id` that phase 3 starts using. It is an internal queue, so it isn't added to the analytics pipeline.
- **`StepSlaScheduleService`:**
  - when a group with `tolerance-days` opens with a known due date, write its `DUE_DATE_REACHED` and `MISSED_DATE_REACHED` rows;
  - write no SLA rows for steps under a group deadline;
  - write the group's `MET_CONDITION_REACHED` row when it completes on time.
- **Closure** (design §7.4): settled members; members never created because a predecessor was written off; optional groups never hold their parent open (point 11). Set `closed_at`, then open what follows, with due dates per point 7.
- **E5 fill-in** when a predecessor completes, per point 8.
- **Hand-off poller:** claims `sla_handoff` rows with `FOR UPDATE SKIP LOCKED`, takes the `protocol_instance` lock, recomputes closure, and opens what follows.

**`cce-step-sla-service`**
- `SlaTransitionFetchRepository`: fetch group rows, locking the group row.
- `SlaTransitionApplier`:
  - **judging:** judge a group row by comparing the group's `completed_at` with the row's threshold, as for steps;
  - **writing:** write `step_group_instance.sla_status` through `SlaStatus.canReplace`, and record the group deviation, its metadata listing the member step rows not completed (A1);
  - **hand-off:** in the same transaction, write an `sla_handoff` row when it writes `MISSED` on a grouped step or on a group.
- Step-sla never takes the `protocol_instance` lock.

**`cce-common-util`:** a group variant of `DeviationRecorder`.

**`cce-protocol-service`**
- Lift the gate on group `tolerance-days`.
- Warn at load for a step deadline under a group deadline (C1) and for nested group deadlines (point 10).
- Reject a deadline on a top-level group with no predecessor (point 9).

**Tests.**
- **The ANC scenarios** (design A1): early arrival is `MET`; late arrival is one `OVERDUE`; never coming is `OVERDUE` + `MISSED`; came but incomplete is `MISSED`.
- **SLA rows:** no step SLA rows under a group deadline.
- **Closure:** closure by write-off opens what follows, with the point 7 due date.
- **E5:** fill-in and its edge cases.
- **Hand-off:** it is idempotent.
- **Concurrency:** a matcher-versus-step-sla race on one database (integration test).

### Phase 3: Intelligence on deviations

**Scope:** E4, D4 and point 13.

**Changes.**
- **Step-sla:** writes an `sla_handoff` row, with its `deviation_id` set, for each deviation it records. No migration: the column exists from phase 2.
- **Matcher:** evaluates intelligence when draining it. This needs a group variant of `IntelligenceActionEvaluator.evaluateOnDeviation`.
- **Rule context:** gains `groupActionId` and `cycleIndex`, for steps inside groups too.
- **Event log:** `intelligence_event_log` rows for group events have `step_status` null.
- **Protocol service:** lift the gate on `fire-event` children of a group.

**Tests.**
- A group `MISSED` fires the group's action.
- Step `OVERDUE` / `MISSED` fires the step's action.
- The rule context includes the cycle number.

### Phase 4: Scheduled repeats

**Load checks** (lift the gate on group `timing`):
- the timing is a `Timing` with `period` and `periodUnit`;
- it uses only fields CCE reads, and only supported units (design §5.3);
- a top-level scheduled group has a `relatedAction` predecessor (E6);
- every mandatory step under it has a deadline at some level (C2, point 3).

**Matcher.**
- **Advancing:** when a cycle closes, stop on `count`; otherwise compute the next due date (points 1 and 2), stop on bounds (`boundsDuration` end excluded, `boundsPeriod.end` included), and open cycle N+1.
- **After the group stops:** a step event that arrives after the group has stopped is recorded with no group (point 17).
- **Late completion in a closed cycle:** recorded, with no follow-on steps.

**Tests.** The design's §7.5 examples table, row by row; closure by write-off opening the next cycle through the hand-off; point 2's fallback.

### Phase 5: Visit-driven and nested repeats

**Load checks.**
- Accept the `cycle-boundary` extension, with `calendar-day` or `entry-step` (point 5).
- A group has either `timingTiming.repeat` or `cycle-boundary`, not both.
- C2 doesn't apply to groups with `cycle-boundary` (point 3).

**Matcher.**
- **`calendar-day`:** the event's clinical day, in the configured zone (point 4), selects the occurrence. Earlier-day events follow point 6.
- **`entry-step`:** a new cycle opens when an entry step repeats after it's already completed in the current cycle (point 5).
- **E3:** opening a cycle closes the earlier open cycles of the same group, as incomplete: for `calendar-day`, those for earlier days (point 6); for `entry-step`, the previous cycle.
- **Inner repeats:** numbered within their parent occurrence. When the parent occurrence closes, its open inner cycles close with it, and no new ones open.

**Configuration:** a calendar-zone setting in the matcher, one per deployment (point 4), documented in its deployment guide.

**Tests.**
- The design's B2 cases.
- Point 6's backdated event.
- A check-in around midnight UTC.
- Two Consultations in one visit.

### Phase 6: Group reporting

Starts once point 15 is answered.

**Changes.**
- **ClickHouse:** views for group occurrences and group verdicts.
- **`cce-insights-service`:** repository, service and controller endpoints.
- **`cce-insights-ui`:** the screens.

---

## 4. Cross-cutting

### 4.1 Concurrency

- **Lock:** every group decision (completion, closure, opening, advancing) runs under the `protocol_instance` lock, taken only when the protocol has groups.
- **Lock order:** the step or group row first, then `protocol_instance`.
- **Step-sla:** it never takes the protocol lock; it only writes hand-off rows.

### 4.2 Documentation

Each phase updates the documentation it changes:
- **`cce-common-util/docs/data-dictionary.md`:** the new table and columns, and column ownership.
- **Architecture overviews:** for the matcher, step-sla, protocol service and common-util.
- **Deployment guides:** `V8` and `V9`, and the calendar-zone setting.
- **A sample PlanDefinition** with groups.

### 4.3 Operations: Event Replay (C3)

Add to the Event Replay sections of the matcher and step-sla deployment guides:
- **During a replay:** stop step-sla and the matcher's hand-off poller.
- **Afterwards:** list the group occurrences closed during the replay window without completing (`closed_at` in the window, `completed_at` null), and any cycles opened from them, for review. Verdicts can't be undone, so the list is what operators act on.

### 4.4 Rollout

Each phase is one release across the repos it touches, deployed in this order:
1. `cce-common-util`;
2. the matcher, including its migration;
3. step-sla;
4. the protocol service last.

Because the protocol service lifts the load-time gate last, no protocol can use a feature before the services support it.

---

## 5. Risks

| Risk | Mitigation |
|---|---|
| A group is treated as a step somewhere, since it is typed `step` | Phase 1 parser tests; ignore matches on group action ids. |
| Group rows in `deviation` / `step_sla_state_transition` distort step-level numbers | Phase 1 audit of insights and pipeline queries, before phase 2 writes any. |
| Cross-service races (matcher versus step-sla) | Integration tests with both services on one database, from phase 2. |
| Phase 3 switches on intelligence for step `OVERDUE` / `MISSED` in protocols already loaded | Before release, list loaded protocols with `fire-event` actions on those deviations, and include them in the release notes. |
| Calendar-day cycles split at the wrong time | Point 4's zone setting; tests around midnight. |
