# Design: Step groups

> **Superseded where decided.** This document is the proposal the customer reviewed, kept unchanged as that record. The questions in §12 have since been decided, and some decisions differ from the proposals here. The decisions, and the further points they settled, are in [implementation-plan-step-groups.md](implementation-plan-step-groups.md) (§1 and §2). Where the two disagree, the implementation plan takes precedence.

**Status:** Proposal. It follows the decisions in [step-group-design-discussion.md](step-group-design-discussion.md). The questions that discussion leaves open are referred to here by its ids (A1–D7); questions this design raises are added as E1–E6 (§12).
**Baseline:** CCE 2.0.0 (`release-2.0.0` of `cce-common-util`, `cce-matcher-service`, `cce-step-sla-service`, `cce-protocol-service`, `cce-data-pipeline`).
**Scope:** Add the **step group**: a set of steps that belong together clinically, written in the PlanDefinition as a FHIR grouping action. A group may nest inside another group, may repeat, and may carry its own deadline.

This revision replaces the earlier model, in which a group was a parent *step* defined by a repeating `Timing`. The discussion's §8.2 lists what carried over from that model.

---

## 1. Summary

- **A group is its own concept.** In the PlanDefinition it is an `action` with `groupingBehavior: logical-group`, child actions, and no trigger. It is never matched to an event; only its steps are.
- **It has its own table.** Each occurrence of a group for a patient is one `step_group_instance` row. Each step points to its direct group occurrence; each nested group points to its parent occurrence.
- **Repeating is optional.** A group may occur once (an ANC visit), repeat on a schedule (a quarterly review), or repeat per visit (the Rwanda patient journey). Each repeat is a new occurrence, called a **cycle**.
- **Complete and closed are different.** A group is *complete* when all its mandatory work is done. It is *closed* when nothing more is expected of it. A group can be closed but incomplete.
- **A deadline sits at one level per branch.** It is either on the group or on its steps, never both, so one missed fact gives one verdict.

Most of the runtime behaviour depends on open questions (§12). §15 orders the work so the parts that don't depend on them can start first.

---

## 2. The step model this has to fit

A step has no single state. It has two independent fields:

| Field | Values | Written by |
|---|---|---|
| `step_status` | `NOT_STARTED`, `COMPLETED` | `cce-matcher-service`, when the step's event arrives |
| `sla_status` | null (not judged yet), `OVERDUE`, `MISSED`, `MET` | `cce-step-sla-service` alone, when it applies `step_sla_state_transition` rows (`DUE_DATE_REACHED`, `MISSED_DATE_REACHED`, `MET_CONDITION_REACHED`) |

What this means for groups:

- **`MISSED` is a verdict, not a terminal step state.** A step whose SLA is `MISSED` is still `NOT_STARTED`, and a late event can still complete it (`StepInstanceService.completeStep`).
- **Every mandatory step already has a deadline: its due date.** `StepSlaScheduleService.schedule` writes a `DUE_DATE_REACHED` row for every mandatory step that has a due date, and the matcher gives every step one:
  - `createInitialStep` uses the time of creation;
  - progressive instantiation uses the `relatedAction` offset;
  - backfill uses the revealing completion's time.

  `tolerance-days` only adds the `MISSED_DATE_REACHED` row (`missedDate = dueDate + toleranceDays`). So a mandatory step can always go `OVERDUE`, and goes `MISSED` only if it has a tolerance. §8.1 depends on this.
- **Optional steps have no SLA rows at all** (`V4__optional_steps_have_no_schedule.sql`) and are never pre-created.
- **Only the matcher schedules deadlines.** The library states it directly: *"nothing in this library can invent a deadline"* (`SlaThresholdReader`).
- **Two services write the same step, from different JVMs, sharing only the database.** The matcher records completions and `cce-step-sla-service` records verdicts.

---

## 3. What exists today

| Piece | Current state |
|---|---|
| Group concept | None. A `step` with nested `step` children is flattened into peer steps; nesting has no runtime meaning. |
| Actions with no `step` / `fire-event` type | Rejected: by `validateActionTypes` at load, and by `extractSteps` / `flattenAction` when a protocol is parsed for an event. |
| Parser walks into nested actions | `indexNestedSubStepTriggers`, `collectConditionOnlyTriggers`, `validateActionTriggers` and `flattenAction` descend into children typed `step`, and `flattenAction` turns every `step`-typed action into a `StepMetadata`. |
| `StepMetadata` | 8 fields, no record of the enclosing action. |
| `groupingBehavior` / `selectionBehavior` | Not parsed. |
| Single-action repeat | `createDependentSteps` creates every occurrence up front when `timing.count > 1`, numbered by `repeat_index`. |
| Tables that record deadlines, deviations, intelligence events | They point at a step only. `step_sla_state_transition.step_instance_id` and `deviation.step_instance_id` are `NOT NULL` foreign keys, each with a unique key on `(step_instance_id, type)`. `intelligence_event_log.step_instance_id` is nullable with no foreign key, but its `step_status` column is `NOT NULL`. |
| Row locking | `cce-step-sla-service` locks transition rows (`FOR UPDATE SKIP LOCKED`) and their step rows (`PESSIMISTIC_WRITE`) in `SlaTransitionFetchRepository`. Nothing locks `protocol_instance`. |
| Protocol completion | Absent. `ProtocolInstanceStatus` has `COMPLETED`, but nothing sets it. |
| PostgreSQL | 16. |

---

## 4. What a group is

### 4.1 Decision: its own concept and table

Decided in the discussion (§4). A group shares property names with a step (status, due date, done date, repeat) but not their meanings. Every reader of `step_instance` assumes one row is one expected clinical event. That includes matching, progressive creation, step-sla and the analytics pipeline. Group rows in that table would need a "skip groups" check in each of them, permanently.

### 4.2 How a group is written

| Element | On a group action |
|---|---|
| `groupingBehavior` | `logical-group`. On a `step`-typed action, this is what makes it a group instead of a standalone step (discussion §7.4). |
| `action[]` | The group's members: steps, nested groups, and `fire-event` intelligence actions (E4). It must contain at least one step, directly or in a nested group. |
| `trigger` | None. A group is never matched directly. |
| `type` | `step`, like every other step-typed action. `validateActionTypes` needs no change. |
| `timingTiming` | Optional. Present for a scheduled repeat (§4.3). |
| `relatedAction` | Optional. When the group is due relative to other actions. |
| `tolerance-days` extension | Optional. The group's deadline (§8). |
| `requiredBehavior` | Whether the group as a whole is mandatory (A3). |
| `selectionBehavior` | Absent or `all` (E1). |

A `step` with nested children and no `groupingBehavior` keeps today's behaviour: flattened, no runtime meaning.

So a `step`-typed action is either:
- **a standalone step:** no `groupingBehavior`, or a presentation value (`visual-group`, `sentence-group`). Nested children are flattened, as today;
- **a group:** `groupingBehavior: logical-group`.

Keeping groups typed `step` means `validateActionTypes`, and the parser walks that descend into `step` children, reach groups and the steps inside them without change. The cost is the reverse: every place that turns a `step`-typed action into a step must now skip groups (§5.2). A `fire-event` action with `logical-group` is rejected; intelligence actions are leaves.

**`selectionBehavior`.** The agreed completion rule ("all mandatory steps done") is the `all` case. Completion logic reads `selectionBehavior` from the start, so branching values (`exactly-one` and so on) can be added later without rework. Until then, absent means `all`, and other values are rejected at load.

### 4.3 Kinds of group

| Kind | Example | A new occurrence starts | Group due date |
|---|---|---|---|
| Single occurrence | ANC visit 2 | — | From `relatedAction`, or from pregnancy dating (A4) |
| Scheduled repeat | Quarterly diabetes review | Every `period` of `Timing.repeat`, until `count` or a bound (`boundsDuration` or `boundsPeriod.end`) is reached | Yes |
| Visit-driven repeat | Rwanda patient journey | Each time the patient comes back (B2, B3) | Usually none |

### 4.4 Nesting

Groups form a tree:
- each step points to its direct group occurrence;
- each nested group occurrence points to its parent occurrence;
- a step belongs to one group at most, because PlanDefinition nesting is itself a tree.

Whether an inner group may repeat within one cycle of its outer group is B1. The earlier rule rejecting nested repeating groups is replaced by whatever B1 decides.

---

## 5. Parsing and validation (`cce-common-util`, `cce-protocol-service`)

### 5.1 Parsed model

- **New `GroupMetadata` record:** `id`, `parentGroupId`, `timing`, `toleranceDays`, `requiredBehavior`, `relatedSteps`, `selectionBehavior`, `intelligenceActions`. Groups are a separate list in `ParsedProtocol`, not entries in `steps()`. `flattenAction` branches on `groupingBehavior`: a `logical-group` action becomes a `GroupMetadata`, never a `StepMetadata`. A group is not a step, so nothing that walks steps should ever meet one.
- **`StepMetadata` gains `groupId`**, its direct group or null. It is a positional record constructed in 36 places (the parser, plus tests in `cce-common-util`, `cce-matcher-service` and `cce-protocol-service`); all change in one pass.
- **`TimingInfo` gains `boundsDuration`** (value and unit) **and `boundsEnd`**, parsed from `Timing.repeat.bounds[x]`. Every form of `bounds[x]` is either supported or rejected at load (§5.3), never ignored. An ignored bound would leave a group with no end, repeating for good.
  - **`boundsDuration`: supported.** How long the schedule runs, counted from cycle 0's due date. This is the natural form for a template applied to patients at different times ("every 3 months for 1 year").
  - **`boundsPeriod.end`: supported.** An absolute end date, for a programme with a fixed calendar end.
  - **`boundsPeriod.start`: rejected.** Supporting it would mean not opening cycle 0 before that date, which nothing needs yet.
  - **`boundsRange`: rejected.** It gives a range of possible lengths, not a point to stop at.
- **A step's own `Timing.repeat` keeps its meaning:** single-action repeat, numbered by `repeat_index`. A group's `Timing.repeat` belongs to the group. Because the group is no longer a step, the two can no longer be confused, and the earlier "root-timing rule" is no longer needed.
- **Dependency graph:** `relatedAction` can be declared on a group or name a group. `buildDependencyGraph` handles steps only, and has to accept group nodes.
- **Helpers:**
  - the group path of a step, from its direct group up to the top;
  - the mandatory members of a group, including nested mandatory groups (subject to A3);
  - the entry members of a group: members with no predecessor inside the same group (§7.1).

### 5.2 Parser walks

Because a group is typed `step`, the walks that descend into `step` children already reach groups and the steps inside them: `validateActionTypes`, `buildTriggerIndexEntries`, `extractConditionOnlyTriggers` and `validateTriggers` need no change to do so. The new checks they gain are in §5.3. `collectActionIds` already descends into every action, so group ids are already required to be unique.

What changes is the opposite direction: a group must never be treated as a step.
- **`flattenAction`** is the one place `step`-typed actions become steps. With the branch in §5.1, groups stay out of `extractSteps()`, and so out of everything built from it: `mustStepIds`, `buildDependencyGraph` (which gains group nodes separately), `findOptionalStepDeadlines` and `ParsedProtocol.step()`.
- **Trigger indexing** also runs on the group action itself. It produces nothing, because a group has no trigger (§5.3).
- **Protocols loaded before these checks** may contain a `logical-group` action that still has a trigger, with rows already in `trigger_index`. The matcher must ignore a match whose action is a group. This follows the existing precedent of `StepSlaScheduleService`, which enforces "optional steps have no deadline" for protocols loaded before that check existed.

### 5.3 Load-time checks (`ProtocolDefinitionService`)

| Check | Reason |
|---|---|
| A group has no trigger | Groups are never matched directly. |
| A group contains at least one step, directly or nested | An empty group can never complete. |
| `logical-group` appears only on `step`-typed actions | A `fire-event` action is an intelligence leaf and has no members. |
| `selectionBehavior` is absent or `all` (E1) | Only `all` has defined completion semantics. |
| At most one deadline on any path from a top group down to a step (C1) | One missed fact, one verdict. A deadline is `tolerance-days` on a group, or on a step. |
| No `tolerance-days` on an optional group (A3) | Mirrors `validateOptionalStepDeadlines`. |
| Repeat-within-repeat rule (B1) | Replaces the earlier ban on nested repeating groups. |
| A group's `timing[x]`, if present, is a `Timing` (`timingTiming`) | The other forms (`timingDateTime`, `timingAge`, `timingPeriod`, `timingDuration`, `timingRange`) are not read, so they are rejected rather than ignored. |
| A group's `timingTiming.repeat` has `period` and `periodUnit` | A scheduled repeat needs a cadence. |
| A group's `timingTiming.repeat` uses only fields CCE reads: `count`, `period`, `periodUnit`, `frequency` of 1, `boundsDuration`, `boundsPeriod.end` | Rejects `boundsRange`, `boundsPeriod.start`, `frequency` above 1, `countMax`, `periodMax`, `dayOfWeek`, `timeOfDay` and `when`, so an author never believes an unread setting takes effect. |
| `periodUnit` and the `boundsDuration` unit are `min`, `h`, `d`, `wk`, `mo` or `a` | The units `StepInstanceService.addOffset` can add. Any other unit (`s`, for example) would throw when the next cycle is computed. |

The one-deadline check is not sufficient on its own. A mandatory step's due date alone gives it an `OVERDUE` deadline (§2), so the matcher must also suppress step SLA rows under a group that has a deadline (§8.1).

---

## 6. Schema

### 6.1 `step_group_instance`

The matcher owns the DDL, and the entities go in `cce-common-util`.

```sql
-- cce-matcher-service/src/main/resources/db/migration/V8__step_group_instance.sql
CREATE TABLE step_group_instance (
    id                       UUID        PRIMARY KEY,   -- app-generated UUID v7
    protocol_instance_id     UUID        NOT NULL REFERENCES protocol_instance(id),
    parent_group_instance_id UUID        REFERENCES step_group_instance(id), -- null for a top-level group
    group_action_id          VARCHAR     NOT NULL,      -- the group's action.id
    cycle_index              INTEGER     NOT NULL DEFAULT 0, -- 0 for a group that doesn't repeat
    due_date                 TIMESTAMPTZ,               -- null when the group has no deadline, or none could be worked out (§7.1)
    sla_status               VARCHAR,                   -- step-sla only; same values as step_instance.sla_status
    opened_at                TIMESTAMPTZ NOT NULL,      -- matcher
    completed_at             TIMESTAMPTZ,               -- matcher; clinical time the last mandatory member was completed (§7.4)
    closed_at                TIMESTAMPTZ,               -- matcher (§7.4)
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT step_group_instance_cycle_key
        UNIQUE NULLS NOT DISTINCT (protocol_instance_id, group_action_id, parent_group_instance_id, cycle_index)
);
CREATE INDEX idx_step_group_instance_protocol ON step_group_instance (protocol_instance_id);
CREATE INDEX idx_step_group_instance_parent ON step_group_instance (parent_group_instance_id)
    WHERE parent_group_instance_id IS NOT NULL;

ALTER TABLE step_instance ADD COLUMN step_group_instance_id UUID REFERENCES step_group_instance(id);
CREATE INDEX idx_step_instance_group ON step_instance (step_group_instance_id)
    WHERE step_group_instance_id IS NOT NULL;
```

- **`NULLS NOT DISTINCT` is required.** Without it, PostgreSQL treats nulls in a unique key as different from each other. Two rows for the same top-level group and cycle (both with a null parent) would then both be accepted, and opening a top-level occurrence would not be idempotent. The clause needs PostgreSQL 15 or later; CCE runs 16.
- **No status column.** "Complete / partly done / not started" is calculated from the steps (discussion §7.2, D2). Only facts are stored:
  - `opened_at`, `completed_at` and `closed_at`, recorded by the matcher;
  - `sla_status`, recorded by step-sla.

  `completed_at` is the one fact the discussion would calculate instead. It is stored because step-sla needs it to judge a group's deadline, and step-sla parses no PlanDefinitions (its build file says so). Without the PlanDefinition, the step rows alone can't show a mandatory member that hasn't been created yet (§8.2).
- **`cycle_index`** means for a group what `repeat_index` means for a step: which repeat of the action this row is.
- **Names** follow the discussion (`step_group_instance`, `step_group_instance_id`), pending D7.

### 6.2 Tables that must point at a group (D1)

| Table | Today | Change (if a nullable group reference is chosen) |
|---|---|---|
| `step_sla_state_transition` | `step_instance_id NOT NULL` + FK; unique `(step_instance_id, transition_type)` | Make `step_instance_id` nullable. Add nullable `step_group_instance_id` + FK, a CHECK that exactly one is set, and a second unique key `(step_group_instance_id, transition_type)`. The existing key ignores rows whose step id is null. |
| `deviation` | `step_instance_id NOT NULL` + FK; unique `(step_instance_id, deviation_type)` | Same pattern. |
| `intelligence_event_log` | `step_instance_id` nullable, no FK; `step_status NOT NULL` | Add nullable `step_group_instance_id`. `step_status` must become nullable, or take a defined value, for group events. |

The alternative is separate group tables for transitions and deviations. Either way it is a one-time change in these tables, not a check in every reader.

---

## 7. Lifecycle (`cce-matcher-service`)

### 7.1 Opening a group occurrence

A group has no trigger, so something else has to open it:

| Situation | Opens when | Due date |
|---|---|---|
| Group with a `relatedAction` predecessor | The predecessor finishes. This is progressive instantiation, as for steps; whether "finishes" means complete or closed is E2. | The predecessor's completion + offset. If it closed without completing, see E2. |
| An event matches a step whose group has no open occurrence | Reactively, with the step | Its planned due date, below. Never the event's time. |
| Top-level group with no predecessor | E2 | None, below |
| Nested group that is an entry member of its parent | When its parent occurrence opens | The parent's due date |
| Next cycle of a scheduled repeat | §7.5 | §7.5 |
| New cycle of a visit-driven repeat | The entry step's event arrives (B2) | Usually none |

**An occurrence's due date never depends on what opened it.** An occurrence opened reactively takes the due date it would have had if it had been opened as planned:

| Case | Due date |
|---|---|
| The group has a `relatedAction` predecessor that has completed | The predecessor's completion + offset, as if progressive instantiation had opened it |
| Next cycle of a scheduled repeat | The cycle's scheduled due date (§7.5) |
| Nested group | Its parent occurrence's due date |
| No planned due date can be worked out yet: the predecessor hasn't completed, a top-level group with no predecessor, or ANC pregnancy dating (A4) | None. The occurrence gets no group deadline rows (§8.1, E5). |

Using the event's time would make a group with its own deadline late almost every time. The first step's event sets the due date and the remaining mandatory steps arrive after it, while `MET` needs all of them by the due date. In ANC visit 2, a woman arriving early at 19 weeks would have her visit judged `OVERDUE` five minutes later, when the BP is recorded. A step doesn't have this problem with `createInitialStep`'s "due now", because the step is created and completed by the same event; a group is opened by its first step and completed by its last.

Opening an occurrence does three things, in one transaction:
1. inserts the `step_group_instance` row, idempotent through its unique key;
2. creates a `NOT_STARTED` step for each mandatory **entry** step;
3. opens each entry nested group, recursively.

Only entry members are created. Later members arrive through progressive instantiation with the due dates their own `relatedAction` offsets define. Creating all members at once would give, for example, a day-3 lab the same due date as the day-0 vitals.

### 7.2 Progression inside an occurrence

`createDependentSteps` deduplicates across the whole protocol instance today (`existsByProtocolInstanceIdAndActionId`). When the completed step and its dependent share a group:
- the dependent joins the same occurrence;
- deduplication is scoped to that occurrence only. Otherwise cycle 1 could never re-create a step that cycle 0 already has.

When the dependent belongs to a group but its predecessor doesn't, the dependent joins its group's open occurrence, opening one if there is none, as `createInitialStep` does (§7.3).

All other edges keep today's behaviour.

### 7.3 Matching an event to an occurrence

- **`findActionableStep`** returns `steps.get(0)` from an unordered query. Once a step can be open in more than one cycle, which one an event completes is arbitrary. Which row it should complete is B6. Whatever is chosen needs an explicit order, which also fixes the same latent bug for `repeat_index` rows.
- **`createInitialStep`** attaches the new step to the open occurrence of its direct group. If there is none, it opens one, and its ancestors' occurrences if they are also not open. Proposal: if a repeating group has stopped (count or bounds reached), the step is recorded with no group, so an event past the protocol's bound doesn't start a new cycle.
- **Visit-driven groups:** the `Encounter` reference most FHIR clinical resources carry is the strongest candidate key for which journey an event belongs to (B2, B6).

### 7.4 Complete and closed

**Complete.** An occurrence is complete when every mandatory member is done. The matcher decides it, and when it happens records the clinical time of the completion that made it so as `completed_at` (§6.1). Nothing else about completeness is stored. When a member counts as done:
- **a mandatory step:** its `step_status` is `COMPLETED`, whatever its `sla_status`. A step completed late still counts as done;
- **a mandatory nested group:** its occurrence is complete.

Members that haven't been created yet count as not done. So the member list comes from the PlanDefinition, not only from the rows that exist.

**Closed** is a stored fact: the matcher sets `closed_at` when nothing more is expected. That is:
- the occurrence is complete; or
- the group has a deadline and the occurrence's `sla_status` is `MISSED`; or
- the deadline is below the group, and every mandatory member is settled.

  "Settled" combines the two fields:
  - **a step** is settled when its `step_status` is `COMPLETED`, or its `sla_status` is `MISSED` (it is still `NOT_STARTED`, but has been written off). `sla_status` `OVERDUE` is not settled: the step can still be done before it is written off;
  - **a nested group** is settled when its occurrence is closed;
  - **a member never created** because a predecessor was written off (`sla_status` `MISSED`, `step_status` still `NOT_STARTED`) is settled too. Without this rule, one written-off step would hold the occurrence open forever, because its dependents are only created when it completes.
- An occurrence with no deadline at any level can only close by completing, unless E3 decides otherwise.

Rules around closure:
- **Only the matcher writes `closed_at`.** When a step-sla verdict is what closes an occurrence, the matcher has to learn of it through a hand-off (B5).
- **Late work after closure (A2).** The occurrence stays closed and its `sla_status` doesn't change, but it can still become complete, and `completed_at` is then set. A late completion must not create new dependents inside a closed occurrence.
- **Shared with protocol instances (D3).** The complete/closed logic is written once, over a set of members, so it can later decide protocol completion too. One consequence: an open-ended repeating group never closes, so protocol completion needs a rule for it, for example counting it as done once its current cycle is closed.

### 7.5 The next cycle

**Scheduled repeat:**
1. The next cycle opens when cycle N closes, or when it completes (B4).
2. **Stop on count:** if `count` is set and `N + 1 >= count`, stop.
3. Compute the next due date: a fixed calendar (`due_date + period`) or a `period` after cycle N actually finished (B4). If cycle 0 has no due date, what the calendar counts from is E6.
4. **Stop on bounds:**
   - if `boundsDuration` is set and the next due date is **at or after** cycle 0's due date + `boundsDuration`, stop. The duration is the schedule's length, so its end is not part of it: "every 3 months for 1 year" is four reviews, not five;
   - if `boundsEnd` is set and the next due date is **after** it, stop. FHIR makes a `Period` end inclusive: "2012-02-03T10:00:00 is in a period that has an end value of 2012-02-03". So a date-only end covers that whole day.

   When `count` and a bound are both set, whichever is reached first stops the group. That is FHIR's reading: "If you have both bounds and count, then this should be understood as within the bounds period, until count times happens."
5. Open cycle N+1 (§7.1).

**Examples.** A quarterly diabetes review: `period: 3`, `periodUnit: mo`. They assume B4 is decided as a fixed calendar, so each cycle is due 3 months after the previous cycle's due date. Under the other B4 option the dates drift later with late work, and a bound can stop the group sooner; the stop checks themselves are the same.

| Timing | Patient's cycle 0 due | Cycles opened (due dates) | What stops it |
|---|---|---|---|
| `count: 4` | 10 Jan 2027 | 10 Jan, 10 Apr, 10 Jul, 10 Oct 2027 | Count: the next cycle would be the 5th |
| `boundsDuration: 1 a` | 10 Jan 2027 | 10 Jan, 10 Apr, 10 Jul, 10 Oct 2027 | Duration: the next due date, 10 Jan 2028, is exactly one year after cycle 0's, so it is outside |
| `boundsPeriod.end: 2027-09-30` | 10 Jan 2027 | 10 Jan, 10 Apr, 10 Jul 2027 | End date: the next due date, 10 Oct, is after 30 Sep |
| `boundsPeriod.end: 2027-09-30` | 1 Jun 2027 | 1 Jun, 1 Sep 2027 | End date: the next due date, 1 Dec, is after 30 Sep |
| `count: 4` + `boundsPeriod.end: 2027-12-31` | 10 Jan 2027 | 10 Jan, 10 Apr, 10 Jul, 10 Oct 2027 | Count, reached first: the next due date, 10 Jan 2028, would also be past the end |
| `count: 4` + `boundsPeriod.end: 2027-12-31` | 1 Jun 2027 | 1 Jun, 1 Sep, 1 Dec 2027 | End date, reached first: the next due date, 1 Mar 2028, is after 31 Dec, before a 4th cycle |

The two bounds behave differently for patients who start at different times. `boundsDuration` gives every patient the same length of schedule, counted from their own cycle 0. `boundsPeriod.end` stops everyone on the same calendar date, so a patient who starts later gets fewer cycles. That suits a programme with a fixed end, and is why `boundsDuration` is the usual choice in a template.

**Visit-driven repeat.** The cycles follow the patient's visits, not a calendar. Example: the Rwanda patient journey, whose entry step is check-in.

| Event | What happens |
|---|---|
| 3 Mar, 09:00: check-in | No open cycle, so cycle 0 opens, and check-in completes in it |
| 3 Mar, 09:20: vitals | Belongs to cycle 0 |
| 17 Mar, 10:30: check-in | The patient is back: cycle 1 opens |
| 17 Mar, 10:45: vitals | Belongs to cycle 1, not cycle 0 |
| The patient never returns | Nothing is due, so nothing is late, and no cycle 2 opens |

Compared with a scheduled repeat:
- a cycle opens when the patient arrives, not on a date set in advance;
- a cycle usually has no due date of its own, so not coming back is not "missed";
- there is no natural count or end.

Two things are still open:
- **B2, what marks a new visit.** "Every check-in" breaks if a patient is checked in twice on one day, or if the EMR skips check-in. If the group has more than one entry step, which of them starts a visit is part of the same question. The alternatives are a new calendar day, or a new `Encounter`. Most FHIR clinical resources reference the `Encounter` they were recorded in, so every event from one visit carries the same id. Keying cycles on it would also settle which cycle an event like vitals belongs to (B6).
- **B3, how an author writes it.** `Timing.repeat` can only say when something repeats on a calendar: `period`, `count`, `bounds`, days of the week, times of day. Its nearest idea, `when`, names daily-life events such as meals and waking, not visits. So "start a new cycle each time the patient checks in" needs a CCE convention, for example an extension on the group that names the step that starts a cycle.

---

## 8. Group deadlines

### 8.1 Scheduling (matcher)

The matcher stays the only service that schedules deadlines.

- **Group rows.** When it opens an occurrence of a mandatory group that has `tolerance-days`, it writes the group's `DUE_DATE_REACHED` row (`due_date`) and `MISSED_DATE_REACHED` row (`due_date + tolerance`). If no due date could be worked out at opening (§7.1), it writes neither, and the occurrence is not judged unless E5 decides otherwise.
- **No step rows under a group deadline.** Steps under that group get no SLA rows at all. Without this, each mandatory step would also go `OVERDUE` on its own due date (§2), and one late visit would raise a group deviation plus one per step. In code, `createStep` passes no thresholds to `StepSlaScheduleService` for a step whose deadline is held by a group. The step's `due_date` is still recorded, for ordering (B6) and display.
- **Completion and MET.** When the last mandatory member completes, the matcher sets the occurrence's `completed_at`. If that is at or before the group's due date, it also writes the group's `MET_CONDITION_REACHED` row, as it does for steps.

### 8.2 Judging (step-sla)

- Group rows are applied exactly like step rows, from stored facts only. Step-sla compares the occurrence's `completed_at` with the row's threshold, as it compares a step's `completed_at` today. It writes `step_group_instance.sla_status` (forward-only, through `SlaStatus.canReplace`) and records the deviation against the group (D1).
- Step-sla does not read the group's steps to decide. It parses no PlanDefinitions, so it can't tell when a mandatory member hasn't been created yet. For example, a step whose only predecessor is optional and never happened has no row, and the remaining rows could all read as done.
- `SlaTransitionFetchRepository` joins and locks each row's step. Group rows need the same with the group row.
- A group `MISSED` verdict closes the occurrence (§7.4), which the matcher learns of through the hand-off (B5).
- **Event Replay (C3).** Group verdicts, and any closures they cause, can be wrong during a replay in the same way step verdicts can, and can't be undone.

---

## 9. Concurrency

Two mandatory members of one occurrence can settle at the same moment. A group verdict can also land while the last member completes. Each transaction may see the other's change as not yet made, so neither closes the occurrence or opens the next cycle.

- **Lock:** before deciding complete or closed, or opening the next cycle, lock the protocol instance with `ProtocolInstanceRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`, added to `cce-common-util`). Take it only when the protocol has groups; protocols without groups pay nothing. It is a PostgreSQL row lock, so it serializes across services as well as across threads.
- **Lock order:** always the step row (in step-sla, the step or group row) first, then `protocol_instance`. The matcher writes the step before any group logic runs, and step-sla locks its batch's rows at fetch time, so both services take locks in the same order.
- **Lock duration:** `SlaTransitionApplier.fetchAndApply` applies a whole batch in one transaction. If step-sla took the `protocol_instance` lock, it would hold it until the batch commits. With a hand-off row drained by the matcher (B5), only the matcher takes it, one row at a time.

---

## 10. Other components

- **Intelligence.**
  - `IntelligenceActionEvaluator` puts `repeatIndex` in the rule context. It gains the group's action id and cycle index next to it, so a rule can tell cycle 0 from cycle 3; names are D4.
  - If groups carry their own `fire-event` actions (E4), they are evaluated on the group's deviations and completion.
- **Data pipeline** (needed even with no group reporting, D5):
  - add `public.step_group_instance`, and any group tables D1 adds, to `table.include.list` in `connectors/debezium-postgres-source.json`;
  - add it to the `cce_analytics_pub` publication in `cdc/01-configure-replication.sql` (`publication.autocreate.mode` is `disabled`);
  - the new `step_instance` column is captured by Debezium automatically, but the ClickHouse tables and Kafka ingestion (`schema/01`, `02`) need it, and the views, rollups and backfill (`03`, `06`, `07`, `09`) need reviewing;
  - rebuild.
- **Documentation.** `cce-common-util/docs/data-dictionary.md` says `step_instance` has "no parent-child column". It needs the new table, the new column, and the column-ownership rows.

---

## 11. The use cases in this model

| | ANC visit 2 (discussion §5) | Rwanda patient journey (discussion §6) |
|---|---|---|
| PlanDefinition | Group action, `logical-group`, `tolerance-days` on the group, due from `relatedAction` or pregnancy dating (A4) | Group action, `logical-group`, repeats per visit (B3), containing a Consultation group |
| Rows | One `step_group_instance` (cycle 0), due at 20 weeks. Its mandatory steps have no SLA rows. | One occurrence per visit, plus one Consultation occurrence per visit pointing to it as parent |
| Deadline | Group: `OVERDUE` at 20 weeks, `MISSED` at 22 weeks | None on the group; steps may carry deadlines relative to check-in |
| Complete | All four mandatory steps have `step_status` `COMPLETED` | All mandatory steps, including Consultation's, have `step_status` `COMPLETED` |
| Closed | Complete, or the group's `sla_status` is `MISSED` | Complete, or E3 |

---

## 12. Open questions

Each question below can be read on its own. It starts with an example scenario, then gives the options and this design's proposal where it has one. Section references are at the end of each question. A1–D7 are the discussion record's ids (its §9); E1–E6 are raised by this design.

The examples reuse three protocols:
- **ANC visit 2:** a group due at 20 weeks of pregnancy and written off at 22 weeks, with mandatory steps encounter, BP, weight and urine test, and optional counselling.
- **Quarterly diabetes review:** a group repeating every 3 months, with vitals on day 0, an HbA1c test on day 3 and a consultation on day 7.
- **Rwanda patient journey:** a group repeating once per visit, starting at check-in, with a nested Consultation group.

### 12.1 At a glance

| Id | Question | Decided by | Proposal |
|---|---|---|---|
| A1 | Came but incomplete: same verdict as never coming? | Clinical | — |
| A2 | Late work after a group has closed: what do reports show? | Clinical | — |
| A3 | Can a whole group be optional? | Clinical | — |
| A4 | ANC due dates from pregnancy dating | Clinical | — |
| B1 | Can an inner group repeat within one outer cycle? | Clinical | — |
| B2 | What starts a new visit-driven cycle? | Both | Leaning: `Encounter` id |
| B3 | How is visit-driven repetition written? | CCE team | An extension on the group |
| B4 | Scheduled repeats: next cycle's due date, and when it opens | Both | Open on closure |
| B5 | Who opens the next cycle when a verdict closes one? | CCE team | Hand-off row |
| B6 | Which open row does an event complete? | Both | Nearest due date |
| C1 | One deadline per branch: reject or warn? | CCE team | Reject at load |
| C2 | Mandatory steps that can never be written off | Both | — |
| C3 | Event Replay and group verdicts | CCE team | — |
| D1 | How do deadline rows, deviations and intelligence events point at a group? | CCE team | — |
| D2 | Store or calculate group status? | CCE team | Calculate; store `completed_at` |
| D3 | Build protocol completion now? | Both | — |
| D4 | Rule-context field names | CCE team | `groupActionId`, `cycleIndex` |
| D5 | Group reporting in the first release? | Product | — |
| D7 | Table naming | CCE team | `step_group_instance` |
| E1 | `selectionBehavior` in the first release | CCE team | Absent means `all`; reject others |
| E2 | When does a group with no trigger open? | Both | — |
| E3 | What closes a group with no deadline anywhere? | Clinical | — |
| E4 | Intelligence actions on groups? | Both | — |
| E5 | A group deadline not known when the group opens | Both | — |
| E6 | What a scheduled repeat counts from when cycle 0 has no due date | Both | Count from cycle 0's opening |

### 12.2 Clinical meaning

**A1. Came but incomplete: should it get the same verdict as never coming?**

*Scenario.* ANC visit 2 has its deadline on the group. Amina comes at 19 weeks; her encounter, BP and weight are recorded, but the urine test isn't done. Bea never comes. A group is `MET` only if every mandatory step is done by the due date, so both visits go `OVERDUE` at 20 weeks and `MISSED` at 22 weeks. The record can't tell Amina from Bea.

*Options.*
- (a) Same verdict. The deviation's details could still list which steps were missing.
- (b) A separate outcome, such as "attended, incomplete". It needs a new value beside `OVERDUE` and `MISSED`, which step-sla, deviations and the dashboards all have to learn.

*See:* §8.2.

**A2. Late work after a group has closed: what should reports show?**

*Scenario.* Amina's visit 2 was written off at 22 weeks because the urine test wasn't done. At 23 weeks the urine test result arrives, and CCE completes the step, since a late event can complete a written-off step. The group keeps `sla_status` `MISSED`, because verdicts only move forward, and it stays closed. But all its mandatory steps are now done, so it becomes complete, and its `completed_at` is set to 23 weeks.

*Options.*
- (a) Reports show "missed, completed late" as a state of its own.
- (b) Reports show only the verdict, `MISSED`.

Either way, the late completion creates no new steps inside the closed group.

*See:* §7.4.

**A3. Can a whole group be optional?**

*Scenario.* An ANC protocol has a "Nutrition counselling" group with `requiredBehavior: could`, containing a diet assessment (`must`) and supplement advice (`must`). Many women won't get counselling, and that's fine. But once a woman does start counselling, is the diet assessment then required?

*Options.*
- (a) No optional groups: reject `could` on a group.
- (b) Allowed. Its `must` steps become mandatory only once the group has started, meaning one of its steps has been matched. Before that, nothing in it is created or judged.
- (c) Allowed, and everything inside it counts as optional.

Whatever the answer, an optional group can't have its own deadline (`tolerance-days`), as for optional steps.

*See:* §5.1, §5.3, §7.4.

**A4. Should ANC due dates come from pregnancy dating?**

*Scenario.* Amina's last menstrual period was 1 Jan 2027, so visit 2 is due at 20 weeks of pregnancy: 21 May, whenever visit 1 happened. CCE today can only set a due date as an offset from an earlier step, such as "6 weeks after visit 1", so a late visit 1 makes visit 2's due date late too.

*Options.*
- (a) Step groups wait for the roadmap's "dynamic due date" item.
- (b) The first ANC protocol approximates, for example with offsets from a registration step.

This doesn't block groups as such. But without it, an ANC visit often has no planned due date when it opens, and so no deadline (E5).

*See:* §7.1, E5.

**B1. Can an inner group repeat within one cycle of its outer group?**

*Scenario.* In the Rwanda journey, a patient sees a doctor in the morning and goes back to the same clinic in the afternoon, on the same visit. Is that two Consultation occurrences inside one journey cycle, or one?

*Options.*
- (a) At most once per visit. A group nested inside a repeating group may not have its own repeat.
- (b) Yes. The Consultation repeats within the visit, its cycles numbered within each journey occurrence.

The schema supports either, because its unique key includes the parent occurrence.

*See:* §4.4, §5.3.

### 12.3 Cycles and matching

**B2. What marks a new visit for a visit-driven group?**

*Scenario.* In the Rwanda journey each visit is one cycle, starting at check-in. Three awkward cases:
1. The patient is checked in at 09:00, leaves, and is checked in again at 14:00 the same day.
2. The EMR doesn't record check-in on 17 Mar, but vitals arrive at 10:45.
3. Vitals from 3 Mar are sent late, on 18 Mar, after the 17 Mar visit has opened a new cycle.

*Options.*
- (a) Every check-in starts a cycle. Case 1 becomes two visits, and case 2 opens no cycle at all.
- (b) A new calendar day. Case 1 is one visit, and case 3 lands on 3 Mar by its clinical time. But a visit that runs past midnight splits in two.
- (c) A new `Encounter`. Most FHIR clinical resources reference the encounter they were recorded in, so all three cases resolve by that id: same encounter, same cycle.

*Leaning:* (c). It would also settle B6 for visit-driven groups.

*See:* §7.1, §7.3, §7.5.

**B3. How is "a new cycle every visit" written in the PlanDefinition?**

*Scenario.* For the quarterly review an author writes `timingTiming.repeat: {period: 3, periodUnit: "mo"}`. For the Rwanda journey they want "a new journey every time the patient checks in", and FHIR has nothing equivalent. `Timing.repeat` only describes calendar repetition, and its `when` field names daily-life events such as meals and waking, not visits.

*Proposal.* A CCE extension on the group that names the step which starts a cycle (URL illustrative):

```json
"extension": [{ "url": "http://openphc.org/fhir/StructureDefinition/cycle-start", "valueId": "check-in" }]
```

If B2 settles on `Encounter`, the extension could instead say "one cycle per encounter".

*See:* §4.2, §5.3, §7.5.

**B4. Scheduled repeats: when is the next cycle due, and when does it open?**

*Scenario.* Quarterly diabetes review. Cycle 0 is due 10 Jan; the patient finishes its work late, on 25 Feb.
- **Due date of cycle 1.**
  - (a) Fixed calendar: 10 Apr, which is 10 Jan + 3 months. This is the rule single-step repeats already follow, and dates never drift.
  - (b) After the work: 25 May, which is 25 Feb + 3 months. It follows the patient, but drifts later with every late cycle.
- **When cycle 1 opens.** Suppose cycle 0's HbA1c is never done and gets written off.
  - (a) When cycle 0 closes. The write-off closes it, so the reviews carry on.
  - (b) When cycle 0 completes. It never completes, so cycle 1 never opens and the reviews stop for good, unless the HbA1c is recorded late.

*Proposal.* Open on closure. If closure can come from a write-off verdict, B5 applies.

*See:* §7.5.

**B6. Which open row does an event complete?**

*Scenario.* Quarterly diabetes review. Cycle 0's vitals (due 10 Jan) were never recorded and have been written off. Cycle 1's vitals (due 10 Apr) are open. Two vitals events arrive: one dated 12 Apr, and a late one dated 20 Jan that is only sent on 15 Apr. Today `findActionableStep` picks either row arbitrarily.

*Options.*
- (a) The row whose due date is nearest the event's clinical time. *Proposal.* The 20 Jan vitals complete cycle 0 and the 12 Apr vitals complete cycle 1, so each is credited to the cycle it belongs to.
- (b) The oldest row first. The 12 Apr vitals fill cycle 0's gap, and the 20 Jan vitals, arriving later, are credited to cycle 1: both land in the wrong cycle. Without the late January event, cycle 1's vitals would go missing instead, recording the patient as behind after they caught up.
- (c) The latest cycle only. The 12 Apr vitals complete cycle 1, but the 20 Jan vitals can never complete cycle 0, which contradicts the rule that a late event completes a written-off step.

For visit-driven groups, the `Encounter` key (B2) answers this directly. Whichever is chosen, the lookup needs an explicit order, which also fixes the same arbitrary pick for repeating single steps today.

*See:* §7.3.

**E2. When does a group open, given it has no trigger?**

*Scenario 1, a group with a predecessor.* ANC visit 3 is due "after visit 2". Visit 2 is written off at 22 weeks because the urine test was never done, so it closes without completing.
- (a) Visit 3 opens when visit 2 completes. It never opens, and its steps are never expected.
- (b) Visit 3 opens when visit 2 closes, at 22 weeks. Its due date can't be counted from visit 2's completion, because there was none, so it has no deadline unless E5 provides one.

*Scenario 2, a top-level group with no predecessor.* A patient is enrolled in the quarterly diabetes review on 10 Jan.
- (a) Cycle 0 opens at enrollment, so its first steps exist and are expected from day one.
- (b) Cycle 0 opens only when one of its steps is first matched. Nothing is expected until the patient comes for vitals, and if they never come, nothing is missed.

*See:* §7.1.

**E6. What does a scheduled repeat count from when cycle 0 has no due date?**

*Scenario.* The quarterly diabetes review is a top-level group with no predecessor, so under §7.1 cycle 0 opens with no planned due date. The patient's first vitals arrive on 10 Jan. When is cycle 1 due? The fixed-calendar rule counts from cycle 0's due date (B4 (a)), and `boundsDuration` counts from it too ("every 3 months for 1 year"). With no due date, both have nothing to count from.

*Options.*
- (a) Require every scheduled repeating group to have a `relatedAction` predecessor, checked at load. This still fails when the group opens before the predecessor has completed (E5).
- (b) Count the cadence and `boundsDuration` from cycle 0's `opened_at` when it has no due date. Cycle 1 is then due 10 Apr. *Proposal:* a starting point for counting is not a deadline, so using the opening time doesn't bring back the "late from the first step" problem of §7.1; cycle 0 still gets no deadline of its own.

*See:* §7.1, §7.5.

### 12.4 Deadlines and closure

**C1. One deadline per branch: reject or warn?**

*Scenario.* An author gives ANC visit 2 `tolerance-days: 14` on the group, and also `tolerance-days: 7` on the BP step. If both applied and the woman never came, CCE would record the group's `OVERDUE` and `MISSED` and the BP's as well: four deviations for one missed visit. Hence the rule: at most one deadline on any path from a top group down to a step.

*Options.*
- (a) Reject the protocol at load. *Proposal.*
- (b) Load it with a warning, and the group's deadline wins.

Either way, the matcher schedules no step deadline rows under a group deadline, because a mandatory step's due date alone already gives it an `OVERDUE` deadline.

*See:* §5.3, §8.1.

**C2. A mandatory step without `tolerance-days` can never be written off.**

*Scenario.* In a quarterly diabetes review with deadlines on its steps, the vitals have `tolerance-days: 7` but the HbA1c has none. The patient never has the HbA1c drawn. The HbA1c goes `OVERDUE` at its due date, but with no missed date it never goes `MISSED`. So it is never settled, cycle 0 never closes, and cycle 1 never opens: the reviews stop silently.

*Options.*
- (a) Require every mandatory step under a repeating group to have a deadline at some level, checked at load.
- (b) Accept that such a cycle closes only when the step is done.

*See:* §5.3, §7.4.

**E3. What closes a group that has no deadline anywhere?**

*Scenario.* The Rwanda journey has no deadlines on the group or its steps. On 3 Mar the patient is checked in and has vitals, but the diagnosis (`must`) is never recorded. Cycle 0 can only close by completing, so it stays open forever. On 17 Mar the patient comes back.

*Options.*
- (a) Accept it. Cycle 0 stays open, and the 17 Mar visit opens cycle 1 anyway, so open visits pile up in the record.
- (b) Opening cycle 1 closes cycle 0, as incomplete.
- (c) A protocol-level maximum, such as "a visit closes 24 hours after it opens".

*See:* §7.4, §7.5.

**E5. What if a group's deadline isn't known when it opens?**

*Scenario.* ANC visit 2 has a deadline (`tolerance-days: 14`) and is due "6 weeks after visit 1". A woman skips visit 1 and walks in for what is clinically visit 2. Her first event opens the visit 2 group, but visit 1 never completed, so there is no date to count 6 weeks from. Using the event's time as the due date would make the group late as soon as its second step is recorded, so the group gets no deadline at all. If she then leaves without the urine test, nothing is ever judged.

*Options.*
- (a) Leave such a group unjudged.
- (b) Set its due date and schedule its deadline later, once the predecessor completes; here, if visit 1 is ever recorded. This bends the rule that `due_date` is written once: it could go from null to a value, once.

Pregnancy dating (A4) would remove the most common ANC case.

*See:* §7.1, §8.1.

**B5. Who opens the next cycle when a verdict closes one?**

*Scenario.* In the quarterly diabetes review, step-sla writes off cycle 0's HbA1c (`MISSED`) on 17 Jan. With that, every mandatory step in cycle 0 is settled, so cycle 0 is closed and cycle 1 should open. But step-sla only records verdicts; only the matcher creates steps and schedules deadlines.

*Options.*
- (a) A hand-off row. *Proposal.* In the same transaction as the verdict, step-sla writes a "check this group" row. A small matcher poller claims it (`FOR UPDATE SKIP LOCKED`), closes cycle 0 and opens cycle 1. This is the existing transition-row pattern, in reverse; cycle 1 opens one poll interval later.
- (b) Move step creation into the shared library, so step-sla can open cycle 1 itself. This breaks the rule that only the matcher schedules deadlines.
- (c) A Kafka event from step-sla to the matcher. It adds a topic between two services that already share a database.

*See:* §7.4, §8.2, §9.

**C3. Can Event Replay produce wrong group verdicts?**

*Scenario.* An outage delays events, and operators replay a week of them. While the replay runs, step-sla can write off a step whose event did happen but hasn't been replayed yet, and step verdicts can't be undone. With groups, the same false write-off can also close a cycle and open the next one.

*Options.*
- (a) Stopping step-sla during a replay is enough.
- (b) The replay runbook needs a group-specific step, such as checking the cycles closed during the replay window.

*See:* §8.2.

### 12.5 Services, schema and rollout

**D1. How do deadline rows, deviations and intelligence events point at a group?**

*Scenario.* Bea never comes to ANC visit 2. CCE has to store the group's two deadline rows (20 and 22 weeks) and two deviations against the visit 2 group, not against any one step. Today `step_sla_state_transition` and `deviation` can only point at a step (`step_instance_id NOT NULL`).

*Options.*
- (a) A nullable `step_group_instance_id` beside `step_instance_id`, with a CHECK that exactly one is set and a second unique key. Every reader of these tables must then expect group rows.
- (b) Separate tables for group deadline rows and group deviations. Existing readers are untouched, which is the same reason groups got their own table, and step-sla needs a separate fetch path for group rows anyway.

`intelligence_event_log` already allows a null step. Either way it needs a group column, and a rule for its `NOT NULL` `step_status` on group events.

*See:* §6.2.

**D2. Store or calculate group status?**

*Scenario.* A dashboard wants "Amina's visit 2: partly done, 3 of 4 mandatory steps". That can be worked out from the step rows whenever it's needed. Storing it would mean updating the group row on every step change, from two services, with the risk of it drifting out of date.

*Proposal.* Calculate it, and store only facts: `opened_at`, `completed_at`, `closed_at` and `sla_status`. `completed_at` is stored because step-sla judges a group's deadline from it and parses no PlanDefinitions (§8.2). The open question is whether any consumer (insights, intelligence rules) needs more stored, for example for speed.

*See:* §6.1.

**D3. Build protocol completion now?**

*Scenario.* Amina's ANC protocol instance stays `ACTIVE` forever today, even after all eight visits: nothing sets it to `COMPLETED`. The complete/closed logic built for groups could decide it, since a protocol instance is the outermost container.

*Options.*
- (a) Build it now, on the shared logic.
- (b) Build it later.

If now, it needs a rule for open-ended groups. The Rwanda journey has no count or end, so it never closes, and the protocol could never complete without, for example, "counts as done once its current cycle is closed".

*See:* §7.4.

**D4. What are the rule-context field names?**

*Scenario.* An intelligence rule should alert only from the third missed quarterly review onwards. Rules today see `repeatIndex` for repeating steps, but nothing about groups or cycles.

*Proposal.* From the discussion: `groupActionId` and `cycleIndex`, so the rule can test `cycleIndex >= 2`.

*See:* §10.

**D5. Is group reporting in the first release?**

*Scenario.* A programme manager asks "how many women completed ANC visit 2 on time this quarter?", which is a group-level number. The pipeline changes that keep CDC working are needed either way. The question is whether insights also gets group-level reports in the first release, or stays step-level as today.

*See:* §10.

**D7. What is the table called?**

`step_group_instance` / `step_group_instance_id`, as in the discussion and this design, or `group_step_instance` from the earlier version. *Proposal:* `step_group_instance`.

*See:* §6.

**E1. Which `selectionBehavior` values does the first release accept?**

*Scenario.* An author marks a "Contraception counselling" group `selectionBehavior: exactly-one`, with three methods as its children, meaning "one of these". CCE's completion rule is "all mandatory steps done", which is FHIR's `all`, so it would wait for all three methods and never complete.

*Proposal.* Absent means `all`. Any other value is rejected at load until branching is designed, so an author can't believe it takes effect.

*See:* §4.2, §5.3.

**E4. Can a group carry its own intelligence actions?**

*Scenario.* When ANC visit 2 is missed, CCE should send a reminder to the community health worker. Today intelligence actions (`fire-event` children) hang off steps and fire on step deviations or completions. Should a group carry its own `fire-event` actions, firing on the group's deviations and completion? D1 already assumes `intelligence_event_log` can point at a group.

*See:* §4.2, §10.

---

## 13. Points to feed back into the discussion record

These came from checking the discussion against the 2.0.0 code. Section numbers in the bold headings are the discussion record's; those at the end of each point are this design's.

1. **A due date is already a deadline (discussion §5.2, C2).** A mandatory step goes `OVERDUE` on its due date whether or not it has `tolerance-days`; `tolerance-days` only adds `MISSED`. So "the author chooses the level by where they put `tolerance-days`" holds only if the matcher also schedules no step rows under a group deadline (§8.1). Likewise, a step with no tolerance can never be `MISSED`, but it can be `OVERDUE`.
2. **The unique key in discussion §7.1 needs `NULLS NOT DISTINCT`**, or top-level occurrences can be duplicated (§6.1).
3. **CDC has no connector column list (discussion §8.1).** The new table has to be added to the connector's `table.include.list` and to the `cce_analytics_pub` publication. New columns are captured automatically, but ClickHouse DDL and ingestion need them (§10).
4. **D1, `intelligence_event_log` (discussion §9).** its `step_instance_id` is already nullable with no foreign key. What blocks group events there is its `NOT NULL` `step_status` column.
5. **`selectionBehavior` default (discussion §7.4).** See E1 and §4.2.
6. **What marks a group (discussion §7.4).** The discussion says both "`groupingBehavior: logical-group`" and "an action with `groupingBehavior` set". These differ for `visual-group` and `sentence-group`. This design reads the rule as `logical-group` only (§4.2).
7. **A group keeps `type: step` (discussion §7.4).** The discussion says a group action carries no type and that `validateActionTypes` must change. This design keeps groups typed `step` and tells them apart by `groupingBehavior: logical-group`. The validator and the parser walks then need no change, and the work moves to keeping groups out of the step list (§5.2).
8. **How a scheduled repeat ends (discussion §6.2).** The discussion lists a fixed `count` or a `boundsPeriod` end. `boundsDuration`, a length counted from each patient's start, fits a template better and is supported here. `boundsRange` and `boundsPeriod.start` are rejected at load rather than ignored (§5.1).
9. **Who works out when a group was done (discussion §5.2, §7.2).** The discussion has step-sla read the group's steps when a deadline row falls due, with no calculated value stored. But step-sla parses no PlanDefinitions, and the step rows alone can't show a mandatory member that hasn't been created yet. So the matcher records the occurrence's `completed_at`, and step-sla judges from that (§6.1, §8.2).

---

## 14. Risks

| Risk | Mitigation |
|---|---|
| A group is treated as a step somewhere, since it is typed `step` | Test that `extractSteps`, `mustStepIds` and the dependency graph never return a group, that a step nested two groups deep is indexed and matched, and that a match on a group's action id is ignored. |
| A group opened by its first step is judged late | Test that a group whose first step arrives early, and whose other steps follow by the planned due date, is `MET`. |
| Double verdicts when a group deadline and step due dates both schedule rows | Test that steps under a group deadline get no `step_sla_state_transition` rows (§8.1). |
| Group lifecycle is entirely new code | Test opening, entry members, deduplication within an occurrence, complete versus closed, stop conditions and B6 row selection directly, not only end to end. |
| A cross-service race goes unseen in tests | A same-JVM test won't reproduce matcher versus step-sla. An integration test needs both writers against one database. |
| CDC silently drops the new table | Ship the pipeline changes (§10) in the same release as `V8`. |

---

## 15. Next steps

1. **Parsing and validation (§5).** Settle E1 and C1, then build it; it changes no runtime behaviour on its own.
2. **Schema (§6)** after D1 and D7, with the pipeline changes (§10) in the same release.
3. **Lifecycle (§7)** after B1–B6, E2, E3 and E6.
4. **Deadlines (§8)** after A1, C1–C3, B5 and E5.
5. **Protocol completion (D3)** on the shared complete/closed logic, when D3 is decided.
