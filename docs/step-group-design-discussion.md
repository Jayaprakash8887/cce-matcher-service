# Design discussion: Step groups

**Status:** Discussion record. One decision is made (§4). The rest are proposals that need agreement, and §9 lists the open questions.
**Date:** 2026-10-05
**Baseline:** CCE 2.0.0 (`release-2.0.0` of `cce-common-util`, `cce-matcher-service`, `cce-step-sla-service`, `cce-protocol-service`). The schema and the split of work between services follow the `docs/` folder of the `cce-common-util` repo.
**Related:** [design-repeating-step-groups.md](design-repeating-step-groups.md). That doc treats a repeating group as a parent *step* with children. This discussion changes that: a group is its own concept, and it may or may not repeat. §8 lists which parts of the earlier doc still apply.

---

## 1. Why this discussion

CCE is adding a new concept, the **step group**: a set of steps that belong together. It is modelled on FHIR's way of grouping actions in a PlanDefinition. The question was how to model it so it stays true to the clinical domain and fits the CCE 2.0 design, so that later features don't have to work around it.

The main question was:

> A group has some of the same properties as a step: a status, a due date, and repeatability. Should a group be treated as a "compound step" and stored as a row in `step_instance`, or should it be its own concept?

Two real-world use cases were used to test the answers:
- **Antenatal care (ANC)**, where each ANC visit is a group (§5).
- **The Rwanda EMR patient journey**, from check-in to referral or transfer, which repeats on different days and contains smaller groups (§6).

---

## 2. Background: what a step is in CCE 2.0

A **step** is defined in the PlanDefinition as an `action` with type `step`. At runtime, each occurrence of a step for a patient is one `step_instance` row.

| Kind | Property | Notes |
|---|---|---|
| Links upward | protocol instance (and so the patient) | `protocol_instance_id` |
| | step id | `action_id`, the step's `action.id` in the PlanDefinition |
| Properties | matched event | `matched_event_id`, `completed_by_source` |
| | match status | `step_status`: `NOT_STARTED` → `COMPLETED`. Did the expected event arrive? |
| | SLA status | `sla_status`: null → `OVERDUE` → `MISSED`, or null → `MET`. Was it on time? |
| | match (clinical) date | `completed_at`, the clinical time of the matched event |
| | due date | `due_date`, written once when the step is created |
| | repeatability | `timingTiming` in the PlanDefinition; each copy gets a `repeat_index` |

Some rules from the 2.0 design matter for this discussion:

- **One writer per column.** The matcher service writes `step_status`, `completed_at` and `due_date`. The step-sla service alone writes `sla_status`.
- **Deadlines are rows.** When the matcher creates a step, it writes the step's deadlines into `step_sla_state_transition`. Step-sla picks those rows up when they fall due and records the verdict. Only the matcher schedules deadlines. The library reference says *"nothing in this library can invent a deadline"*.
- **Only mandatory steps have deadlines.** Optional (`could`) steps have no SLA rows, and their `sla_status` stays null.
- **`MISSED` is a verdict, not an end state.** A missed step is still `NOT_STARTED`, and a late event can still complete it.
- **Nesting has no runtime meaning today.** A `step` with nested `action.action[]` children is flattened, and the children become ordinary peer steps. A step group is a different thing from this existing parent-child nesting.

---

## 3. The new concept: step group

A step group is a set of steps that belong together clinically. As first described, it has:
- a **collective status** (is the group done?)
- a possible **due date**
- **repeatability** (the whole group can happen again)

It is never matched to an event directly. Only its steps are.

---

## 4. Decision: a group is its own concept, in its own table

**A group is not stored in `step_instance`.** It gets its own table, `step_group_instance`, and each step row points up to the group it belongs to.

### 4.1 Same name, different meaning

The properties a group shares with a step have the same names but mean different things:

| Property | For a step | For a group |
|---|---|---|
| Status | **Observed.** An event arrived or it didn't. Two values only. | **Calculated** from its steps. It needs states a step never has, such as "partly done". |
| Due date | A **deadline**, judged against one `completed_at`. | Can be a deadline (ANC, §5), or absent (Rwanda journey, §6). Its steps' due dates are often worked out from it. |
| Done date | The clinical time of one matched event. | Calculated: when the last mandatory step was done. |
| Matched event, source | Always means something. | Never applies. Always empty. |
| Repeatability | N copies of the same step. | N cycles, each with a new set of all its steps. |

Only repeatability means roughly the same thing for both. The rest look alike but don't behave alike.

### 4.2 What breaks if a group is a row in `step_instance`

Every reader of `step_instance` assumes **one row = one expected clinical event**. A group row breaks that assumption. Each of these readers would need an "unless it's a group" check, now and in every future feature:

| Reader | What would go wrong |
|---|---|
| Matcher, `findActionableStep` | Could hand an incoming event to a group row. |
| Matcher, progressive creation, backfill, order-violation check | Would treat the group as a step with its own predecessors and dependents. |
| Step-sla service | Would judge the group as a step and raise its own `OVERDUE`/`MISSED` on top of its steps' deviations, so one miss counts twice. |
| Column ownership | A group's status depends on both `step_status` (matcher) and `sla_status` (step-sla). Both services would have to write the same row, which breaks the one-writer rule. |
| Analytics (CDC → ClickHouse → insights) | Every compliance count and dashboard number would include groups as if they were steps until each query filters them out. |

### 4.3 FHIR points the same way

In a PlanDefinition, FHIR uses the same element (`action`) for both groups and steps. In FHIR's runtime form of a plan, R4 `RequestGroup`, an action has no status element of its own; status lives on the actual resources the plan creates. FHIR also treats a group as a container, not as something that happens.

### 4.4 The cost

Some tables can only point at a step today. If a group can have its own deadline (§5), the tables that record deadlines, deviations and intelligence events must also be able to point at a group. This is a one-time, contained change, not a check in every reader. The exact shape is open (§9, D1).

---

## 5. Use case 1: Antenatal care (ANC)

WHO's 2016 ANC model recommends eight contacts: the first by 12 weeks of pregnancy, then at 20, 26, 30, 34, 36, 38 and 40 weeks. The proposal is to model **each ANC visit as a group**.

An illustrative group (the step list is an example, not a clinical specification):

```
ANC visit 2  (group, due at 20 weeks, written off at 22 weeks)
├── anc2-encounter    must
├── anc2-bp           must
├── anc2-weight       must
├── anc2-urine-test   must
└── anc2-counselling  could
```

### 5.1 Does an ANC visit have a deadline?

Yes. If she has not come by about 22 weeks, visit 2 is missed. So a group can have a deadline. The question is **which level is judged: the visit, or each step inside it?**

Here is how the outcomes differ, using CCE 2.0 rules: work done by the due date is `MET`; done after the due date but before the missed date is `OVERDUE`; not done by the missed date is `MISSED`. A step that goes overdue and is then missed records two deviations.

| Scenario | Deadline on each step | Deadline on the group only |
|---|---|---|
| Comes at 19 weeks, all four mandatory steps done | 4 steps `MET` | Group `MET` |
| Comes at 21 weeks, all done | 4 `OVERDUE` deviations | 1 `OVERDUE` deviation |
| Never comes | 8 deviations (4 `OVERDUE` + 4 `MISSED`) | 2 deviations (1 `OVERDUE` + 1 `MISSED`) |
| Comes at 19 weeks, urine test not done | Urine test `OVERDUE` then `MISSED`; the others `MET` | Group `OVERDUE` then `MISSED`, the same as never coming (see §9, A1) |

A clinician thinks "she missed ANC visit 2", not "she missed BP, weight and urine". For ANC, **the deadline belongs on the group.**

Other groups need the opposite. In a quarterly diabetes review where vitals are on day 0, the HbA1c test on day 3 and the consultation on day 7, each step has its own date, so **each step needs its own deadline.**

### 5.2 Proposal: a deadline lives at one level only

- The protocol author chooses the level by where they put `tolerance-days`.
- If a group declares `tolerance-days`, its steps may not have their own. The protocol service rejects the PlanDefinition at load time if they do.
- If a group declares none, its steps may have deadlines, as today.
- With nested groups (§6), the same rule applies down each branch: **at most one deadline on any path from the top group down to a step.**

So one missed fact always gives one verdict, never two.

How it fits the 2.0 split of work:

| Concern | Service | How |
|---|---|---|
| Scheduling the group's deadline | Matcher | Writes the group's deadline rows when it opens the group. It stays the only service that schedules deadlines. |
| Judging the group's deadline | Step-sla | When a row falls due, reads the group's steps and works out when the last mandatory step was done. No calculated value needs storing for this. |
| Recording the verdict | Step-sla | Writes `step_group_instance.sla_status` (same values as a step's) and records the deviation. |

### 5.3 What ANC teaches about groups

1. **A group does not have to repeat.** The eight ANC visits are eight different groups: their contents differ (an early ultrasound, for example, is only expected at the first contact) and the gaps between them are uneven. Repeatability is an optional property of a group, not what makes it a group. The earlier doc defines a group by its repeating; that definition has to change.
2. **"Came but didn't finish" is different from "didn't come".** Under the agreed completion rule (§7.3), a woman who came on time but had no urine test has an unfinished group. Whether that deserves the same verdict as never coming is open (§9, A1).
3. **ANC due dates count from the start of pregnancy, not from the previous visit.** Visit 2 is due at 20 weeks because of her last menstrual period (LMP), not because it is some weeks after visit 1. Today CCE only sets due dates from when an earlier step happened (`relatedAction` offsets). This is the "dynamic due date" item on the roadmap. It doesn't block groups, but a realistic ANC protocol will need it (§9, A4).

---

## 6. Use case 2: Rwanda EMR patient journey

In the Rwanda EMR journey, every step from patient check-in to referral or transfer sits inside one group, because **the whole journey repeats: the patient comes back on different days.** Smaller groups can sit inside the journey, such as a "Consultation" group.

Illustrative structure:

```
Patient journey  (group, repeats; one cycle per visit)
├── check-in              must
├── triage / vitals       must
├── Consultation          (group)
│   ├── history           must
│   ├── examination       must
│   └── diagnosis         must
├── lab order / result    could
├── dispensing            could
└── referral / transfer   could
```

### 6.1 Groups can be nested

- `step_group_instance` gets a nullable `parent_group_instance_id`.
- Each step points to its **direct** group only.
- A step cannot belong to two sibling groups, so groups and steps form a simple tree.
- The earlier doc rejects nested repeating groups (`validateNoNestedRepeatingGroups`). That rule has to be relaxed or removed. How far depends on whether an inner group can repeat inside one cycle of its outer group (§9, B1).

### 6.2 A second kind of repeating

The journey repeats in a different way from the diabetes review in the earlier doc:

| | Scheduled repeat (quarterly diabetes review) | Visit-driven repeat (Rwanda journey) |
|---|---|---|
| A new cycle starts | on a calendar, every `period` | when the patient walks in (a check-in event) |
| Group due date | yes | probably none; deadlines, if any, sit on steps relative to check-in |
| Number of cycles | fixed `count` or a `boundsPeriod` end | open-ended |
| FHIR expression | `Timing.repeat` | no direct equivalent in `Timing` |

The visit-driven kind raises a matching question that scheduled groups barely have: **when a vitals or diagnosis event arrives, which journey does it belong to?** The strongest candidate key is the `Encounter` reference that most FHIR clinical resources carry, but this needs agreement (§9, B2).

---

## 7. Proposed model

### 7.1 Table sketch

```
step_group_instance
  id                         UUID v7, app-generated
  protocol_instance_id       FK → protocol_instance
  parent_group_instance_id   FK → step_group_instance; null for a top-level group
  group_action_id            the group's action.id in the PlanDefinition
  cycle_index                0-based; 0 for a group that doesn't repeat
  due_date                   null when the group has no deadline (e.g. the Rwanda journey)
  sla_status                 written by step-sla only; same values as step_instance.sla_status
  opened_at, closed_at       facts recorded by the matcher; "complete" is calculated from the steps

  unique (protocol_instance_id, group_action_id, parent_group_instance_id, cycle_index)

step_instance
  + step_group_instance_id   nullable FK → step_group_instance; null for steps outside any group
```

Migration ownership follows the existing rule: matcher owns the DDL (its next migration is `V8`), and the entities go in `cce-common-util`.

### 7.2 Store as little calculated data as possible

A group's "complete / partly done / not started" status can be calculated from its steps whenever it is needed. Stored calculated values drift out of date, so only store facts that **cannot** be recalculated:
- `opened_at` and `closed_at`, because closing a cycle is what opens the next one;
- `sla_status`, because it is a verdict made at a point in time, with one writer.

### 7.3 Completion and closure

**Agreed:** a group is complete when **all of its mandatory steps** are done, the same principle as for a protocol instance.

That leads to two proposals:

1. **A protocol instance is the outermost group.** The tables stay separate, but the "is this container finished?" logic should be written once and shared. Automatic protocol completion was removed and never rebuilt, so groups will be the first place this logic exists.
2. **Complete and closed are two different ideas.** If a mandatory step is never done, "all mandatory steps done" never becomes true. The group would stay open forever, and a repeating group would never start its next cycle.
   - **Complete:** all mandatory work was done.
   - **Closed:** nothing more is expected, because everything is done or the deadline has passed.

   A group can be **closed but incomplete**. The same distinction applies to protocol instances.

### 7.4 How a group is written in the PlanDefinition

The team prefers the FHIR-recommended way. In FHIR R4, a group is an `action` with **`groupingBehavior: logical-group`** and child actions. For CCE that means:

- **A group action has `groupingBehavior` set, child actions, and no trigger.** The protocol service should reject a group that has a trigger, since groups are never matched directly.
- **`validateActionTypes` must change.** Today every action must be typed `step` or `fire-event`. A group action carries no such type, so the validator must accept an action that has `groupingBehavior` set instead.
- **Groups and plain nesting stay distinct.** An action with `groupingBehavior` is a group. A `step` with nested children and no `groupingBehavior` keeps today's behaviour (flattened, no runtime meaning).
- **The group's own settings use the existing elements:**
  - `timingTiming` for repeatability;
  - `relatedAction` for when the group is due relative to other actions;
  - the `tolerance-days` extension for its deadline;
  - `requiredBehavior` for whether the group as a whole is mandatory (§9, A3).
- **`selectionBehavior` is the future hook for branching.** The agreed completion rule is the `all` case. Values such as `exactly-one` (pick one arm) change what "complete" means. Completion logic that reads `selectionBehavior` from the start lets branching arrive later without rework.

---

## 8. Impact, and what carries over from the earlier doc

### 8.1 Components touched (expected, not yet designed in detail)

| Component | Change |
|---|---|
| `cce-common-util` | Parser recognises group actions and records each step's direct group; new entity and repository; shared completion/closure logic; intelligence rule context gains group and cycle fields so a rule can tell cycle 0 from cycle 3. |
| `cce-matcher-service` | `V8` migration; opening groups and cycles; attaching steps to the right cycle; scheduling group deadline rows. |
| `cce-step-sla-service` | Judging group deadline rows by reading the group's steps; writing group `sla_status`; recording group deviations. |
| `cce-protocol-service` | New load-time checks: group has no trigger; deadline at one level per branch; group typing via `groupingBehavior`. |
| `step_sla_state_transition`, `deviation`, `intelligence_event_log` | Must be able to point at a group as well as a step (§9, D1). |
| `cce-data-pipeline` | Schema changes do not flow through CDC by themselves: the new table and column need the connector column list, ClickHouse DDL and views updated, then a rebuild. This applies even if insights adds no group reporting yet. |
| `cce-common-util/docs/data-dictionary.md` | Currently says `step_instance` has no parent column. It needs the new table, the new column and the ownership rows. |

### 8.2 What carries over from `design-repeating-step-groups.md`

| Earlier doc | Status after this discussion |
|---|---|
| A group root is itself a `step` (§1, §4) | **Replaced.** A group is its own action kind, marked with `groupingBehavior`, with no trigger. |
| A group is defined by its repeating `Timing` | **Replaced.** Repeating is optional. |
| `validateNoNestedRepeatingGroups` | **To revisit.** Nesting is needed (§6.1). |
| `group_step_instance` table | **Kept in spirit**, renamed `step_group_instance`, and extended with parent group, deadline and SLA status. |
| Open a cycle by creating only its "entry" steps; later steps arrive through their own `relatedAction` offsets | **Still applies.** |
| Progressive creation deduplicates within a cycle, not across the whole instance | **Still applies.** |
| Lock `protocol_instance` before checking whether a cycle is done; lock the step row first, then `protocol_instance` | **Still applies.** |
| Q1 (when a cycle is done), Q2 (who opens the next cycle), Q3 (which row an event completes) | **Still open.** Carried into §9. |

---

## 9. Pending discussion

These questions came up and are not yet settled. They are grouped by who is best placed to answer them.

### A. Clinical meaning (needs clinical and programme input)

- **A1. Came but incomplete.** At an ANC visit's deadline, if she came but a mandatory step (the urine test) was not done, should the group get the same `MISSED` verdict as never coming? Or should "visit happened but incomplete" be a separate outcome?
- **A2. Late work after closure.** CCE 2.0 lets a late event complete a `MISSED` step. If that step's group is already closed, does the late event change anything at group level? The group's `sla_status` cannot change, because SLA writes only move forward. But the calculated "complete" status would turn true. Is "closed incomplete, later completed" a state the reports should show?
- **A3. Optional groups.** Can a whole group be optional (`requiredBehavior: could`), for example a counselling group? If so, what does "mandatory" mean for a step inside an optional group?
- **A4. Due dates from pregnancy dating.** ANC visits are due by gestational age (from LMP), not by time since the previous visit. Does step-group work wait for the roadmap's "dynamic due date" item, or does the first ANC protocol use an approximation?

### B. Repeating and nesting

- **B1. Repeating inside a repeat.** Can an inner group, such as Consultation, happen more than once within one patient journey (two consultations on one day)? Or at most once per journey? The answer decides how far the nested-repeat ban is relaxed.
- **B2. What starts a new journey?** For visit-driven groups: every check-in event, a new calendar day, or a new `Encounter` id? This also decides which cycle an incoming event belongs to.
- **B3. How visit-driven repetition is written in FHIR.** `Timing.repeat` describes calendar repetition only. How does an author say "a new cycle every time the entry step happens"?
- **B4. When the next scheduled cycle is due** (earlier doc, Q1). Is cycle N+1 due a fixed `period` after cycle N's due date (a calendar), or a `period` after cycle N actually finished? And is the next cycle opened when cycle N is *complete* or when it is *closed*?
- **B5. Who opens the next cycle when a deadline closes it** (earlier doc, Q2). If a step-sla verdict closes cycle N, only the matcher may create cycle N+1's steps. The earlier proposal was a hand-off row that step-sla writes and the matcher picks up. Moving step creation into the shared library would go against the documented rule that only the matcher schedules deadlines.
- **B6. Which open row an event completes** (earlier doc, Q3). When the same step is open in more than one cycle, which one does an event complete? The earlier proposal was the row whose due date is nearest the event's clinical time. With visit-driven groups, the `Encounter` key from B2 may answer this more directly.

### C. Deadlines and closure

- **C1. Agree the "deadline at one level" rule** (§5.2), including whether the protocol service rejects a PlanDefinition that breaks it, or only warns.
- **C2. Steps with no tolerance can never close a group.** A step with no `tolerance-days` gets no missed-date row, so it can never be `MISSED`. If a group's deadline sits on its steps and a mandatory step has no tolerance and is never done, the group can never close. Should every mandatory step under a repeating group be required to have a deadline at some level?
- **C3. Event Replay.** Group verdicts, and any cycle closures caused by them, come from step-sla. During an Event Replay they could be wrong in the same way step verdicts can, and they cannot be undone. Does the replay runbook need a change, or is "stop step-sla during replay" enough?

### D. Schema and services

- **D1. Pointing at a group.** `step_sla_state_transition`, `deviation` and `intelligence_event_log` can only reference a step today (`step_instance_id`, `NOT NULL` on the first two). Options: add a nullable group reference with a check that exactly one of the two is set, or use separate group tables. Same decision for each table.
- **D2. Store or calculate group status.** §7.2 proposes calculating "complete / partly done" from the steps. Is there a consumer (insights, intelligence rules) that needs it stored?
- **D3. Shared completion logic with protocol instances.** Should protocol-instance completion be rebuilt at the same time, using the same logic, or later?
- **D4. Intelligence rule context.** Agree the field names for group and cycle in the rule context (for example `groupActionId`, `cycleIndex`), next to the existing `repeatIndex`.
- **D5. Analytics scope.** Is group-level reporting needed in the first release, or only the pipeline changes that keep CDC working?
- **D6. Protocols already loaded.** Do any PlanDefinitions loaded in Rwanda already set `groupingBehavior`? If so, they would start behaving as groups after this change. Scan them before enabling.
- **D7. Naming.** Agree `step_group_instance` / `step_group_instance_id` (this doc) or `group_step_instance` (earlier doc).
