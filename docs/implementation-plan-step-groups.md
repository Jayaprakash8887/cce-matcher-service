# Implementation plan: Step groups

**Status:** Draft for review.
**Based on:** [design-repeating-step-groups.md](design-repeating-step-groups.md) (the design), the decisions in §1, and the points in §2 with their status. All are as of 2026-11-04. Question ids (A1–E6) are the design's.
**Baseline:** CCE 2.0.0 (`release-2.0.0` of `cce-common-util`, `cce-matcher-service`, `cce-step-sla-service`, `cce-protocol-service`, `cce-data-pipeline`, `cce-insights-service`).

**Approach.** Phase 1 builds the whole data model. Later phases add behaviour. Some phases add something an author writes in a PlanDefinition. Such a phase is switched on by lifting a load-time check in `PlanDefinitionParser.validateGroups` (`cce-common-util`). The protocol service runs that check at load. Until the phase ships, a PlanDefinition that uses its feature is refused at load. So no feature can be half-active in production.

Two kinds of behaviour have no such switch:
1. phase 4's completion and closure. They reach instances already open in production through a one-off pass at release.
2. intelligence on step `OVERDUE` / `MISSED` (phase 6). It applies to protocols already loaded (§5).

**Code names.** Method and class names are as they were when each phase was built. The step-group code was reorganised later. [architecture-overview.md §6.5](architecture-overview.md#65-step-groups), under "Where the code is", has the current names.

Phases 1 and 2 are all required before production. So are the data-pipeline, insights-service and insights-ui updates for them, which are taken up after phase 2 (§4.4). Scheduled repeats (phase 3) come after the first production release. Protocols without groups behave exactly as today throughout.

---

## 1. Decisions

| Id | Decision | What it means for the build |
|---|---|---|
| A1 | (a) Same verdict | A group with a deadline goes `OVERDUE` / `MISSED` whether the patient came and left work undone or never came. The group deviation's metadata lists the member steps that weren't completed. |
| A2 | (b) Verdict only | Reports show the group's `sla_status`. The matcher still records `completed_at` when late work completes a closed group; reports don't show it. |
| A3 | (b) Optional groups allowed | For now every group is optional: a group can't declare `requiredBehavior`, and mandatory groups come in a later phase (point 11). An optional group doesn't open with its parent, or when a step it follows completes. It opens when one of its steps is matched, or when work creates one of its `must` steps (point 11). Once it is open, its `must` steps become mandatory within it and are created. It never counts towards its parent's completion, started or not (point 11). It can't carry `tolerance-days`. |
| A4 | (b) Approximate | ANC protocols set visit due dates as offsets from a registration step. No code change. |
| B1 | (b) Inner repeats allowed | An inner group may repeat within one instance of its outer group. Its cycles are numbered within that instance. |
| B2 | (b) Calendar day | For a visit-driven group, an event opens a new cycle if its clinical day is later than the latest cycle's. The clinical day is taken in the deployment's time zone (point 4). Any other event goes to the latest cycle (F1, point 6). |
| B3 | Extension, value-driven | A `cycle-boundary` extension on the group, with a coded value (§2, point 5). |
| B4 | Open on schedule | The next scheduled cycle opens on its schedule, whether or not the previous one has completed or closed (point 20). Its due date follows a fixed calendar (points 1 and 2). This replaces the design's proposal, open on closure. |
| B5 | (a) Hand-off row | Step-sla writes a row that the matcher drains. Only the matcher creates steps and schedules deadlines. |
| B6 | (a) Nearest due date, for steps outside groups | For a repeated step outside any group, `findActionableStep` picks the open row whose due date is nearest the event's clinical time. A step in a group follows F1 instead (point 12). |
| C1 | (b) Warn | A step deadline under a group deadline is logged as a warning at load. The group's deadline applies; the step's `tolerance-days` is ignored. |
| C2 | Dropped (was (a)) | No load check. A mandatory step under a scheduled group may have no `tolerance-days`. It then goes `OVERDUE` but is never written off, so its cycle stays open. Under B4 the next cycle opens anyway (point 20). |
| C3 | (b) Runbook step | The Event Replay runbook gains a group-specific step (§4.3). |
| D1 | (a) Nullable reference | `step_sla_state_transition`, `deviation` and `intelligence_event_log` gain `step_group_instance_id`. Exactly one of the step and group references is set. |
| D2 | Calculate | Only facts are stored: `opened_at`, `completed_at`, `closed_at`, `sla_status`. |
| D3 | Deferred | Not in this release. Protocol instances stay `ACTIVE`, as in 2.0.0 today. The group completion logic is written so protocol completion can reuse it later. |
| D4 | As proposed | The rule context gains `groupActionId` and `cycleIndex`. |
| D5 | Yes | Group reporting in insights. Instances come in the first production release (after phase 2). Verdicts come in phase 5. Scope: point 15. |
| D7 | `step_group_instance` | Table and column names as in the design. One row is a *group instance*. Code and docs use that term, after the table, not "occurrence". |
| E1 | `all` only | `selectionBehavior` absent or `all`; other values rejected at load. |
| E2 | Scenario 1 (b), scenario 2 (b) | A group whose predecessor is a group opens when that predecessor closes (phase 4). Until then nothing can follow a group (point 27). A top-level group with no predecessor opens when one of its steps is first matched. |
| E3 | (b) | Opening a visit's cycle closes the previous visit's cycle, as incomplete. Built with closure, in phase 4 (F2). |
| E4 | Yes, on deviations | A group's `fire-event` actions fire on the group's deviations. The matcher evaluates them through a hand-off from step-sla. That hand-off also switches on intelligence for step `OVERDUE` / `MISSED` (point 13). |
| E5 | (b) Fill in later | A group may open before its due date can be worked out. It then gets the due date, and its deadline rows, when its predecessor completes. If the predecessor is a group, this also happens when it closes (point 8). Built in phase 4. |
| E6 | (a) Require a predecessor | A scheduled repeating group, always top-level (point 23), must have a `relatedAction` predecessor, checked at load. |
| F1 | Latest cycle | An event for a step in a group goes to the group's latest cycle, whatever its clinical date. There it completes the cycle's open row for the step, or becomes a further row. An earlier cycle's row is never completed by a later event (point 12). |
| F2 | Completion and closure in phase 4 | Phases 1–3 never record a group as complete or closed. Completion, closure, E3 and the hand-off are built in phase 4 with group deadlines. Until then nothing can follow a group (point 27). |

---

## 2. Points the decisions left open

The decisions in §1 left some cases open. In some, two decisions conflicted. In others, a decision didn't cover a case. This section lists them as points 1–32. Each point gives an example, its resolution and the phase it affects. Each resolution is labelled with its status:
- ***Decided:*** settled by an explicit decision.
- ***Accepted proposal:*** an engineering detail whose proposal stands unless a reviewer objects.
- ***Open:*** no answer yet.

| # | Point | Status | Phase |
|---|---|---|---|
| 1 | When the next scheduled cycle is due | Decided | 3 |
| 2 | A scheduled cycle with nothing to count from | Decided | 3 |
| 3 | The deadline rule (C2) would reject the Rwanda journey | Fell away: C2 is dropped | — |
| 4 | Which time zone a calendar day is in | Decided | 2 |
| 5 | How a group says what starts a new cycle | Decided | 2 |
| 6 | Late events dated to an earlier day | Decided (F1) | 2 |
| 7 | The due date of a group opened because its predecessor closed | Accepted proposal | 4 |
| 8 | Edge cases of filling in a due date later (E5) | Accepted proposal | 4 |
| 9 | A deadline on a top-level group with no predecessor | Accepted proposal | 4 |
| 10 | Nested groups that both have deadlines | Accepted proposal | 4 |
| 11 | Optional groups and completion | Decided | 1, 4 |
| 12 | Which row an event completes | Decided (F1) | 1 |
| 13 | Intelligence on `OVERDUE` / `MISSED` deviations | Decided | 6 |
| 14 | Protocol completion rules | Fell away: protocol completion is deferred | — |
| 15 | Which group reports | Open | after 2, 5 |
| 16 | `step_status` on group intelligence events | Accepted proposal | 1 |
| 17 | Events after a scheduled group has stopped | Decided (F1) | 3 |
| 18 | Order violations and backfill across cycles | Accepted proposal | 1, 4 |
| 19 | A deadline on a visit-driven group | Decided | 1 |
| 20 | When the next scheduled cycle opens | Decided | 3 |
| 21 | A repeating group as a member of its parent | Decided | 4 |
| 22 | `closed_at` when a group instance closes without completing | Accepted proposal | 4 |
| 23 | Which repeating groups may nest inside which | Decided | 2, 3 |
| 24 | Withdrawn enrollments | Decided | 3 |
| 25 | An event for a step already completed in its instance | Accepted proposal | 1–3 |
| 26 | What opens a visit-driven cycle | Decided | 2 |
| 27 | What follows a group before completion exists | Decided (F2) | 1, 4 |
| 28 | Which instance a new row joins | Accepted proposal | 2, 3 |
| 29 | Steps with no due date | Decided | 1 |
| 30 | A predecessor in a repeating group, seen from outside it | Decided | 2 |
| 31 | What a group waits on, when one of its steps comes first | Decided | 2 |
| 32 | An entry step that follows a step outside its group | Decided | 2 |

**1. When is the next scheduled cycle due?** *(B4; blocks phase 3)*
B4 settles when the next cycle opens (point 20). The design left its due date open. Example: a quarterly review. Cycle 0 is due 10 Jan, and its work finished on 25 Feb. Cycle 1 is due either 10 Apr (fixed calendar) or 25 May (3 months after the work).
*Decided:* fixed calendar. Single-step repeats already follow this rule.

**2. A scheduled cycle with nothing to count from.** *(E6 + E5 + B4; blocks phase 3)*
E6 requires a predecessor so that cycle 0 has a due date. But a group still opens early if one of its steps is matched before the predecessor completes. Its due date then waits for E5's fill-in (phase 4). If the predecessor never completes, it never gets one. Example: a quarterly review due "1 week after diagnosis". The patient's first vitals arrive on 10 Jan, and the diagnosis is never recorded. When cycle 1 is to open, the fixed calendar has nothing to count from. `boundsDuration` has no start either.
*Decided:* when cycle 0 has no due date, count the cadence and `boundsDuration` from its `opened_at`. A starting point for counting is not a deadline, so cycle 0 still isn't judged late.

**3. C2 would reject the Rwanda journey.** *(C2 + E3)*
No longer needed now: C2 is dropped (§1). Visit-driven cycles close through E3. Scheduled ones open on schedule, whatever state the previous cycle is in (point 20).

**4. Calendar day in which time zone?** *(B2; blocks phase 2)*
Event times are stored in UTC. A check-in at 23:30 UTC on 3 Mar is 01:30 on 4 Mar in Kigali, so the time zone decides which visit it belongs to.
*Decided:* a deployment setting names the zone (`Africa/Kigali` for Rwanda, `Africa/Nairobi` for Kenya). Every calendar-day group in that deployment uses it.

**5. A repeating inner group can't be separated by calendar day.** *(B1 + B2 + B3; blocks phase 2)*
Two Consultations on one visit day fall on the same calendar day, so calendar days can't tell them apart. Each repeating group therefore needs its own rule for what starts a cycle. That rule goes in the PlanDefinition, as a value. This is also the answer to B3's "value-driven".
*Decided:* one extension on the group, with a coded value:
- `calendar-day`: a new cycle for each clinical day on which the group has events. This is the Rwanda journey.
- `entry-step`: a new cycle with each event for the group's entry step. Example: history at 10:00, then examination, is one Consultation. History again at 15:00 opens a second. The one exception is a latest cycle that still awaits its entry step (point 32): the event then completes it there.

```json
"extension": [{
  "url": "http://openphc.org/fhir/StructureDefinition/cycle-boundary",
  "valueCode": "calendar-day"
}]
```

An `entry-step` group names its entry step, by action id, in a second extension:

```json
"extension": [
  { "url": "http://openphc.org/fhir/StructureDefinition/cycle-boundary",   "valueCode": "entry-step" },
  { "url": "http://openphc.org/fhir/StructureDefinition/cycle-entry-step", "valueId": "history" }
]
```

The entry step:
- is required, exactly once, on every `entry-step` group;
- is a step inside the group, directly or in one of its nested groups. An event for it starts the group's next cycle, and the nested groups around it open fresh inside the new cycle;
- waits on nothing inside the group. A step that waits on another in the group can't start a cycle.

At load, the protocol service rejects:
- an `entry-step` group without exactly one `cycle-entry-step`;
- a value that isn't a `valueId`;
- a name that isn't a step inside the group with no predecessor inside it;
- the extension on any other action.

Why the entry step is named rather than worked out from the group's structure: a step's `relatedAction` would then carry two meanings. Say a Consultation holds history and BP, and BP has no `relatedAction` to history, because it can be taken first. BP would then be an entry step too, and a second BP in the same visit would open a second Consultation. The only way to stop it would be to make BP follow history. That would also change BP's order and due date. With the entry step named, only history starts a new cycle.

Opening a cycle, or a nested group inside it, never creates the entry step. Its own event creates it, or the work that expects it (point 32). An `entry-step` protocol written before this must add the extension to load.

Scheduled repeats keep `timingTiming.repeat`. A group has one or the other, never both. A further rule could come later, such as one cycle per `Encounter`. It would be a new code, plus the code that handles it.

*Not chosen:*
- an extension naming the step that starts a cycle, instead of a code. It can't express `calendar-day`, so it conflicts with B2.
- entry steps worked out from the group's structure: its steps with no predecessor inside it. A step's `relatedAction` would then decide both its order and whether it starts a cycle (above).

**6. Late events for an earlier day.** *(B2 + F1; blocks phase 2)*
The patient visited on 3 Mar and 17 Mar. Vitals dated 10 Mar arrive on 18 Mar, after the 17 Mar cycle opened. No cycle exists for 10 Mar.
*Decided* (F1):
- the vitals go to the latest cycle, the 17 Mar visit. No cycle is opened for an earlier day;
- a new `calendar-day` cycle opens only for an event whose clinical day is later than the latest cycle's day;
- a cycle's day is not stored. CCE keeps clinical event time in UTC, and the cycle is opened by the first event of its day. So its day is `opened_at`'s date in the deployment's time zone (point 4). The enrollment lock (§4.1) holds one cycle per day. No index can hold it, since the time zone is a deployment setting;
- `cycle_index` numbers cycles in the order they were opened.

**7. The due date of a group opened because its predecessor closed.** *(E2; blocks phase 4)*
ANC visit 3 is due "6 weeks after visit 2". Visit 2 is written off at 22 weeks without completing. Visit 3 opens (E2), but there is no completion to count 6 weeks from.
*Accepted proposal:* count from the predecessor's `closed_at`, here the write-off time. This applies to anything, step or group, that follows a group. Anything that follows a step keeps today's rule: it is created only when the step completes.

**8. E5 fill-in edge cases.** *(E5; blocks phase 4)*
- **The group has already finished.** The predecessor may complete after the group is already complete or closed. A deadline filled in afterwards would then judge finished work. *Accepted proposal:* fill in only while the group is open and not complete.
- **Steps created with the group.** Steps created when the group opened take the group's due date. While that is unknown they get none, and so no deadline either. They can't go `OVERDUE` or be written off. *Accepted proposal:* fill in those steps' due dates and deadlines at the same moment, for steps that still have none. This bends the write-once rule for step due dates as well as group ones.
- **A predecessor that is a group.** A group can close without completing (point 7). A follower opened early would then never get its due date. *Accepted proposal:* fill in when a group predecessor closes too, counting from its `closed_at`.

**9. A deadline on a top-level group with no predecessor can never apply.** *(E2 + E5; blocks phase 4)*
Such a group opens when one of its steps is first matched (E2), so it has no planned due date. With no predecessor, E5 never fills one in. So its `tolerance-days` would never schedule anything.
*Accepted proposal:* reject it at load, as the protocol service already rejects a deadline on an optional step.

**10. Nested groups that both have deadlines.** *(C1; blocks phase 4)*
C1 says the group's deadline wins over a step's. Say ANC visit 2 has a deadline and contains a "Lab tests" group that also has one. Which applies?
*Accepted proposal:* the outermost, with a warning at load. Then there is one verdict per visit.

**11. Optional groups and completion.** *(A3; blocks phases 1 and 4)*
*Decided:*
- for now, every group is optional. A group can't declare `requiredBehavior`: `validateGroups` refuses one at load, after the permanent rules, as a phase gate. Its steps keep their own `requiredBehavior`. Mandatory groups come in a later phase, not yet scheduled. Their code stays (`membersCreatedOnOpen`, `openFollowingGroup`), and their tests are disabled until the gate lifts (`ClinicVisit`, `TwoPrerequisites`, `NestedGroupFollowsAStepOutsideItsParent`).

  What this means for now: no group opens with its parent, and none opens when a step it follows completes. A group's own `relatedAction` only plans its due date. To have a group's steps expected after an earlier step, put the `relatedAction` on those steps. Example: `visit-2 { bp, 6 weeks after registration; weight, 6 weeks after registration }`. When registration completes, BP and weight are created, due 6 weeks later, and that work opens `visit-2`.

  Decided on 2026-11-05, at the customer's request. Until then, a group could declare `must`;
- an optional group doesn't open on its own account. Its parent opening doesn't open it, and neither does a step that the group itself follows (`openFollowingGroup`). It opens in two ways:
  - an event for one of its steps, as optional steps are today (phase 1);
  - work for one of its `must` steps (`groupInstanceForWork`). That is progressive instantiation, when the step follows a step outside the group that has completed, or backfill, when the step is a skipped mandatory predecessor. The step is mandatory, so it is expected even though its group is optional. The group then creates its first members, like any group.

  Example: an optional `lab` group, whose `lab-order` (must) follows registration by 3 days. When registration completes, `lab` opens and `lab-order` is due 3 days later. An optional `review` group that itself follows registration does not open.

  The first decision was that an optional group is never opened in advance. On 2026-11-05 it was changed to let work for a `must` step open it, which the code already did (`StepGroupLifecycleTest.OptionalGroupOpenedByWork`);
- it never counts towards its parent's completion or closure, whether it has started or not (phase 4). Its own completion is tracked on its own. Example: a "Nutrition counselling" group inside ANC visit 2 starts, but its diet assessment isn't done. Visit 2 is still complete once its own mandatory steps are.

**12. Which row an event completes.** *(B6 + F1; blocks phase 1)*
The design proposed B6: the open row whose due date is nearest the event's clinical time. The customer has decided otherwise for steps in groups (F1).
*Decided:*
- **a step in a group:** the event goes to the group's latest cycle, whatever the event's clinical date. The latest cycle is the instance opened most recently, compared from the top-level group down. If that cycle's row for the step is open, the event completes it. Otherwise it becomes a further row there (point 25). An earlier cycle's open row is never completed by a later event;
- **a repeated step outside any group:** the open row whose due date is nearest the event's clinical time (B6). Two rows equally near: the earlier. A row with no due date ranks after dated ones, oldest first.

Example: quarterly review. Cycle 0's vitals (due 10 Jan) were never recorded. Cycle 1 (due 10 Apr) is open. Vitals dated 20 Jan, sent only on 15 Apr, complete cycle 1's row, not cycle 0's.

**13. Nothing evaluates intelligence on `OVERDUE` or `MISSED` today.** *(E4; blocks phase 6)*
Group deviations are `OVERDUE` and `MISSED`, recorded by step-sla. Step-sla doesn't evaluate intelligence. Its code says "Evaluating and publishing intelligence on a deviation is not part of this service yet". In 2.0.0 only `ORDER_VIOLATION`, recorded by the matcher, fires intelligence. A step's `fire-event` action conditioned on `MISSED` doesn't fire either.
*Decided:*
- **Step-sla** judges group deadlines and records group deviations (phase 4). For each deviation it records, step or group, it writes a hand-off row. This is the same mechanism as B5.
- **The matcher** drains those rows and evaluates the intelligence actions. Step-sla stays free of Kafka and PlanDefinitions.
- **Steps too:** under E4, intelligence is driven by deviations, step or group. So this also switches on intelligence for step `OVERDUE` / `MISSED` (phase 6). That changes behaviour for protocols already loaded that have `fire-event` actions on those deviations.
- **Groups fire on deviations only,** not on completion as steps do.

**14. Protocol completion rules.** *(D3)*
No longer needed now: protocol completion is deferred (§1, D3). The three rules return when it is built:
- what counts as completed;
- open-ended groups;
- re-enrollment after completion.

**15. Which group reports?** *(D5; blocks the reporting after phase 2, and phase 5)*
*Open.* A minimal first set is proposed, to confirm with product. The instance part is built after phase 2, for the first production release. Completion, closure and verdicts need phase 4. The proposed set:
- per group, by facility and period: instances opened (after phase 2), completed and closed without completing (phase 5);
- group instances, with their cycle numbers, on the patient timeline (after phase 2);
- per group: `MET` / `OVERDUE` / `MISSED` (phase 5).

**16. `intelligence_event_log.step_status` for group events.** *(D1; blocks phase 1)*
The column is `NOT NULL`, and a group has no `step_status`.
*Accepted proposal:* make it nullable.

**17. Events after a scheduled group has stopped.** *(B4 + F1; blocks phase 3)*
A quarterly review with `count: 4` has had its four cycles. A fifth set of vitals arrives. Opening a fifth cycle would go past what the protocol asked for.
*Decided* (F1): the event goes to the last cycle, as any event goes to the latest cycle (point 12). It completes that cycle's open row, or becomes a further row there (point 25). No fifth cycle opens. The design's §7.3 rule recorded such an event with no group. That rule isn't needed.

**18. Order violations and backfill across cycles.** *(B1 + B4; blocks phases 1 and 4)*
`detectOrderViolations`, `backfillMissingMandatorySteps` and the fan-in wait in `createDependentSteps` look a predecessor up by its action id across the whole enrollment. Once a group repeats, one cycle sees another's rows. Say a prescription follows an examination in the Rwanda journey. On 3 Mar the examination isn't recorded, and its row is still `NOT_STARTED` when the patient comes back. On 17 Mar both are recorded in order. But the 3 Mar row is still outstanding, so the prescription raises an `ORDER_VIOLATION`. The other way round, a predecessor missing from the 17 Mar visit isn't backfilled, because the 3 Mar visit has a row for it.
*Accepted proposal:*
- a predecessor may be in the same group as the completed step, or in one of its enclosing groups. It is then looked up in that shared instance, and a missing one is backfilled into it. Any other predecessor is looked up across the enrollment, as today (phase 1);
- a step in a closed instance is not outstanding (phase 4).

**19. A deadline on a visit-driven group.** *(B3 + E3; blocks phase 1)*
A `cycle-boundary` cycle opens when the patient comes back. So it usually has no planned due date (design §4.3). Its `tolerance-days` would rarely, if ever, schedule anything.
*Decided:* reject `tolerance-days` on a group with `cycle-boundary`, as point 9 does for a top-level group with no predecessor. It is a permanent check from phase 1.

**20. When the next scheduled cycle opens.** *(B4; blocks phase 3)*
B4 opens a scheduled cycle on its schedule, whatever state the previous one is in. It doesn't say at what moment. Example: a quarterly review. Cycle 0 is due 10 Jan, and cycle 1 is due 10 Apr. Cycle 1 has to exist before 10 Apr, so that its steps are pending and can be judged.
*Decided:*
- cycle N+1 opens halfway between cycle N's due date and its own (24 Feb here). If that moment has passed, it opens at once. Under F1 every event goes to the latest cycle (point 12). Suppose cycle N+1 opened as soon as cycle N fell due. A visit made a few days after that would go to cycle N+1. Cycle N's work would be left undone, and cycle N+1's done early. Opening it halfway sends a visit to the cycle whose due date it is nearer. It still gives cycle N+1's steps half a period's notice. A cycle 0 with no due date counts from its `opened_at` (point 2);
- opening the next cycle doesn't close the previous one. Each closes by its own rules from phase 4, unlike a visit (E3);
- the stop checks (`count`, bounds) run when the next cycle is scheduled, so a group that has stopped schedules nothing more (point 17);
- if E5 later fills in cycle 0's due date (phase 4), cycles already opened keep theirs.

*What this changes in the design:*
- **§7.5, step 1:** "the next cycle opens when cycle N closes, or when it completes" no longer holds. The rules above replace it. Steps 2–5 stand, with the stop checks run when the next cycle is scheduled. The fixed-calendar examples still hold.
- **B4:** the design proposed open on closure. In its scenario, cycle 1 now opens on 24 Feb, halfway to its due date of 10 Apr. The reviews carry on whether cycle 0's HbA1c is done late, written off, or never settled.
- **B5:** a verdict no longer opens the next scheduled cycle. The hand-off is still needed. A write-off can still close a group instance, and what follows a closed group opens then (point 7). ANC visit 3 opens this way after visit 2 is written off.
- **C2:** its scenario assumed open on closure. In it, an HbA1c that can never be written off stops the reviews. Under open on schedule the reviews go on, and only that cycle stays open. C2 is dropped (§1).

**21. A repeating group as a member of its parent.** *(B1 + B4; blocks phase 4)*
A Rwanda visit holds a Consultation that can repeat within the visit (point 5). The visit is complete when its mandatory members are done. But nothing said when a member that repeats is done, or settled.
*Decided:*
- done once any of its cycles in the parent instance is complete. A visit with two Consultations, one complete, is complete once its other mandatory members are;
- settled once every cycle opened in the parent instance is closed.

Only visit-driven groups can repeat inside a parent: a scheduled group is always top-level (point 23).

**22. `closed_at` when a group instance closes without completing.** *(D2 + E3; blocks phase 4)*
Point 7 counts what follows a closed group from its `closed_at`. If `closed_at` were the moment the hand-off poller happened to run, those due dates would shift with poll timing.
*Accepted proposal:* `closed_at` is always a clinical or planned time, never a processing time:
- closed through its members: the latest time a mandatory member settled. That is a completion's clinical time, a write-off's missed date, or a nested group's `closed_at`;
- written off by its own deadline: the group's missed date;
- closed by E3: the `opened_at` of the cycle whose opening closed it;
- closed with its parent: the parent's `closed_at`.

A complete instance's `closed_at` stays its `completed_at`.

**23. Which repeating groups may nest inside which.** *(B1 + B3 + B4; blocks phases 2 and 3)*
Point 5 gives an inner group `entry-step`. But nothing stopped a `calendar-day` group inside a visit, where every event falls on the same day. An inner `calendar-day` group there could never reach a second day. Nor did anything stop a scheduled group inside any group. A scheduled group inside a visit would restart its schedule with every visit. Inside a one-off group it would clash with point 21. The parent would be complete, and so closed, once one review cycle was done. No further cycle could then open inside it.

An `entry-step` cycle is different: it is not one visit. It lasts until the entry step comes again, often weeks or months later. Example: a pregnancy, started by registration, with an ANC visit on each day she comes.
*Decided:*
- a scheduled group is always top-level; nested, it is rejected at load. No current protocol needs one;
- a `calendar-day` group inside a `calendar-day` group is rejected at load, at any depth, even with an `entry-step` group in between. A group inside a `calendar-day` group can repeat by `entry-step`;
- a `calendar-day` group inside an `entry-step` group is allowed. Each level is decided on its own `cycle-boundary`. A visit's event joins the latest episode, and opens a new visit on a new day. The episode's entry step starts the next episode, with its visits numbered from cycle 0 again;
- a visit-driven group inside a one-off group is allowed.

**24. Withdrawn enrollments.** *(B4; blocks phase 3)*
`ProtocolInstanceStatus` has `WITHDRAWN`. Point 20's schedule would keep opening cycles for a withdrawn patient. Each would have its own deadlines and `MISSED` deviations. An open-ended one would never stop.
*Decided:* the cycle poller drops the schedule rows of an enrollment that isn't `ACTIVE`.

**25. An event for a step already completed in its instance.** *(F1; blocks phases 1–3)*
A second set of vitals arrives during a quarterly cycle whose vitals row is already completed. No open row awaits it in the latest cycle (point 12), so it is recorded as a new step.
*Accepted proposal:* it becomes a further completed row in the group's latest cycle (point 12). This matches how a repeated event outside a group adds a row today. It creates no step the cycle already has. Two exceptions open a new cycle instead:
1. an event for the entry step in an `entry-step` group, unless the latest cycle still awaits it (points 5 and 32);
2. an event for a later day in a `calendar-day` group (point 6).

From phase 4, a complete or closed instance still takes the event, but nothing is created inside a closed one (A2).

**26. What opens a visit-driven cycle.** *(B2 + E2; blocks phase 2)*
Phase 1 opens a group in advance when its predecessor completes, or when its parent opens. For a `calendar-day` journey that follows registration, that would open a cycle on the registration day (point 6). If the first visit comes on another day, it opens a new cycle. The first cycle stays behind as an empty visit that never happened. From phase 4, E3 closes it as incomplete. An `entry-step` group has the same problem. A Consultation that follows check-in would open when check-in completes, with history awaiting. The first history would then complete that awaiting history rather than start a Consultation. But the entry step should say when a cycle starts.
*Decided:*
- only an event for one of its steps opens a `calendar-day` or `entry-step` cycle. A predecessor completing, its parent opening, progressive instantiation and backfill open none. Work for a group with no cycle yet waits for that event. The one exception is an `entry-step` group's expected entry step (point 32);
- an event for any of an `entry-step` group's steps opens its cycle 0 when there is none. So a BP taken before history opens it, without history: opening a cycle never creates the entry step. The history that follows starts cycle 1;
- consequence: unless point 32 applies, history is not awaited, and never judged, until the Consultation starts;
- a step inside a `calendar-day` group may have a `relatedAction` to a step outside it. It keeps its own offset in the first visit only (point 29). Each later visit creates it again, with no due date.

**27. What follows a group before completion exists.** *(E2 + F2; blocks phases 1 and 4)*
Phases 1–3 never record a group as complete or closed (F2). Anything that follows a group, such as ANC visit 3 after visit 2, is created only when the group completes or closes (E2, point 7). Before phase 4 it would never be created. An event for one of its steps would open it with no due date.
*Decided:* until phase 4, the protocol service rejects a PlanDefinition in which anything follows a group. A group can still follow a step.

**28. Which instance a new row joins.** *(F1; blocks phases 2 and 3)*
Points 12 and 25 say which cycle an event goes to. But the matcher adds other rows to a group's instance too:
- a step that follows a step in another group;
- a backfilled step outside the completed step's instance;
- a nested group opened inside its parent;
- the instance a new step is attached to when an event creates it.

Phase 1 has one instance per group, so each of these lookups takes cycle 0. Once groups repeat, that would put new work in a cycle long past.
*Accepted proposal:* wherever the matcher adds to a group's instance, it takes the group's latest cycle, inside its parent's latest cycle. Point 12 does the same for events. Point 18's shared instance, when there is one, comes first. The rules that open a new cycle (points 5, 6 and 20) are unchanged.

**29. Steps with no due date.** *(E2 + E5; blocks phase 1)*
A step created when its group opens takes the group's due date (point 8). That also applied to a step that follows a step outside the group, such as "BP, 7 days after registration". BP took the visit's due date, or none, instead of its own offset. Some groups open with no planned due date, such as a top-level group with no predecessor (E2), or ANC visit 2 reached before visit 1. Such a group gives the other steps created with it no due date, and so no deadline rows. They never go `OVERDUE` or `MISSED`. Before groups, every step had a due date (design §2). Giving them `opened_at` instead would make a visit's second step `OVERDUE` minutes after its first. Design §7.1 describes that problem for groups.
*Decided:*
- a step that follows nothing takes the group's due date. With none, it stays unjudged and shows as pending, until E5 fills in a due date (phase 4), if it ever does. Every reader of `step_instance.due_date` must allow null (after phase 2);
- a step that follows a step outside the group keeps its own offset, like a step in no group. In cycle 0 it is due when the step it follows is done, plus its offset; with several, the latest counts. Its own offset wins over the group's due date;
- while the step it follows isn't done, it isn't created when the group opens. Progressive instantiation creates it when that step is done;
- in a later cycle it has no due date, as every step in a later cycle: the offset would already be past;
- "A later cycle" is a later cycle of the step's own group or of any group around it, up to the group that also holds the step it follows. Example: in `visit [calendar-day] { imaging { scan, 7 days after registration } }`, `imaging` is cycle 0 in every visit, but a later visit is still a later cycle, so its `scan` has no due date. In `pregnancy [entry-step: registration] { registration; visit [calendar-day] { bp, 7 days after registration } }`, each pregnancy holds its own registration, so the next pregnancy's first visit is not a later cycle for `bp`;
- *for a mandatory group, in the later phase (see point 11):* a nested group that follows a step outside its parent follows the same rule as a step. It doesn't open with its parent while that step isn't done. It opens when that step is done, due by its own offset, even in a later cycle of its parent, because the offset is then still ahead. When it opens with a parent in a later cycle, it has no due date, and nor do its first steps. Example: `visit [calendar-day] { lab, 7 days after registration { lab-order } }`. Before this, `lab` opened with every visit, and in every visit it took registration + 7 days, so a later visit's `lab-order` was overdue as soon as it was created (decided 2026-11-05). For now, with every group optional, the due-date part already applies: a nested group opened by an event or by work in a later cycle of its parent has no due date, and nor do its first steps (`OptionalNestedGroupFollowsAStepOutsideItsParent`).

**30. A predecessor in a repeating group, seen from outside it.** *(B1 + B2 + F1; blocks phase 2)*
Point 18 reads a predecessor inside the instance it shares with the completed step, and any other predecessor across the enrollment. Once a group repeats, "across the enrollment" sees every cycle. Say a top-level treatment follows the journey's prescription. On 3 Mar the prescription isn't recorded, and its row is still `NOT_STARTED`. On 17 Mar the prescription and then the treatment are recorded in order. But the 3 Mar row is still outstanding, so the treatment raises an `ORDER_VIOLATION`. The other way round, a prescription missing from the 17 Mar visit isn't backfilled, because the 3 Mar visit has one.
*Decided:* some predecessors are in a group that shares no instance with the completed step. Such a predecessor is read in its group's latest cycle, inside its parent's latest cycle, where point 28 adds work. A predecessor outside every group is still read across the enrollment, since it happens once per enrollment. This applies wherever point 18 does: order violations, backfill and the fan-in wait. It also applies to a group's planned due date.

**31. What a group waits on, when one of its steps comes first.** *(E2 + A4; blocks phase 2)*
Backfill creates the mandatory predecessors of the work observed so far. But it follows only the `relatedAction` edges between steps. A predecessor declared on a group is missed. Say ANC visit 2 follows registration, and a BP for visit 2 arrives before registration has been recorded. The BP step names no predecessor of its own. So registration is never backfilled and never goes `OVERDUE`, and the gap stays invisible. Declared on the BP step instead, registration would have been backfilled.
*Decided:* a step also waits on what its enclosing groups wait on, at every level. Backfill follows those edges. So registration is backfilled when no row for it exists. It goes into its own group's instance if it has one, by points 28 and 30. Order violations still look only at a step's own immediate predecessors.

**32. An entry step that follows a step outside its group.** *(E2 + A4; phase 2)*
With point 26, an entry step could have a `relatedAction` to a step outside its group, such as g2-step-1 2 days after step-1. That did nothing for its SLA. No instance existed until its event, which created it due on arrival. A patient who never came was never reported. The offset and `tolerance-days` looked as though they were in effect when they were not. Also, when BP opened cycle 0, the entry step was created awaiting there. The history that followed went to cycle 1, so the awaiting one went `MISSED` though it happened.
*Decided:*
- when the step the entry step follows completes, progressive instantiation opens the group's cycle 0. It does so only if the group has none inside the parent's instance. It creates the entry step `NOT_STARTED`, due by its own offset. This applies only to cycle 0. A later cycle already holds its entry step. Backfill, a parent opening and other work still open none;
- an event for the entry step completes an awaiting entry step in the latest cycle. Otherwise it starts the next cycle;
- a cycle's opening never creates the entry step. A cycle opened by BP holds no history. The history that follows starts cycle 1, as the customer decided in point 26.

Consequence: a step that follows the entry step, such as examination, may be recorded before it. It then backfills the entry step into its cycle. The entry step's event then completes it there rather than starting another cycle.

---

## 3. Phases

| Phase | Delivers | Needs | Repos |
|---|---|---|---|
| 1. Foundation | One-off and nested groups, all schema changes, row choice; no completion or closure | A3, D1, D2, D7, E1, E2 (scenario 2), F1, F2; points 11, 12, 16, 18, 19, 25, 27, 29 | common-util, matcher, protocol |
| 2. Visit-driven and nested repeats | The Rwanda journey | B1, B2, B3, F1; points 4–6, 23, 25, 26, 28, 30–32 | common-util, matcher, protocol |
| After 2. Reporting for production | The pipeline for the new table and columns; instances opened; the patient timeline | D5; point 15 | pipeline, insights, insights-ui |
| 3. Scheduled repeats | The quarterly review | B4, E6, F1; points 1, 2, 17, 20, 23–25, 28 | common-util, matcher, protocol, insights, insights-ui |
| 4. Completion, closure and group deadlines | Group completion and closure, what follows a group, the hand-off, group verdicts and deviations | A1, A2, B5, C1, E2 (scenario 1), E3, E5, F2; points 7–11, 18, 21, 22, 27 | common-util, matcher, step-sla, protocol |
| 5. Group completion and verdict reporting | Completed and closed instances, and group `MET` / `OVERDUE` / `MISSED`, in reports, without intelligence | A2, D5; point 15 | pipeline, insights, insights-ui |
| 6. Intelligence on deviations | Intelligence on step and group `OVERDUE` / `MISSED`; rule context | E4, D4; point 13 | common-util, matcher, step-sla, protocol |

**Order.** Phases 1 and 2, and the reporting for them after phase 2, are the first production release (§4.4). Phase 1 can start now. The dependencies:
- Every later phase needs phase 1.
- Phase 3 also reuses phase 2's latest-cycle lookups (point 28). Otherwise phases 2 and 3 don't depend on each other.
- Phase 4 needs 2 and 3, since it adds completion and closure to their cycles, E3's included.
- Phases 5 and 6 need 4. Phase 5 needs it for completion, closure and verdicts to report. Phase 6 needs it for group deviations and the hand-off.
- Phases 5 and 6 are independent of each other. Phase 5 waits on point 15, so phase 6 can ship first if that is still open.

Protocol completion (D3) is not part of this plan.

### Phase 1: Foundation

**Scope.**
- Groups that occur once, nested to any depth, with steps attached to their group's instance.
- No completion or closure (F2). A group instance is opened and its steps attached. But its `completed_at` and `closed_at` stay null until phase 4. So nothing can follow a group yet (point 27).
- Deadlines stay on steps exactly as today.
- All schema changes for every phase, except the internal queues (phases 3 and 4). So the analytics pipeline changes once, after phase 2.

**`cce-common-util`**
- `PlanDefinitionParser`:
  - **New records and fields:** a `GroupMetadata` record (design §5.1); `StepMetadata.groupId`.
  - **Groups kept apart:** `flattenAction` branches on `groupingBehavior: logical-group`, so a group never becomes a `StepMetadata`.
  - **Dependency graph:** it accepts groups as nodes.
  - **Helpers:** a group's direct members, everything inside it, and the members it creates when it opens (A3-aware).
- Update the 36 `StepMetadata` constructor calls across `cce-common-util`, `cce-matcher-service` and `cce-protocol-service` (mostly tests).
- `ParsedProtocolCache.ParsedProtocol` gains `groups()` and `group(id)`.
- `validateGroups(PlanDefinition)`:
  - **Permanent checks:**
    - a group has no trigger;
    - a group contains at least one step, directly or nested;
    - `logical-group` only on `step`-typed actions;
    - `selectionBehavior` absent or `all`;
    - no `tolerance-days` on an optional group;
    - no `tolerance-days` on a group with `cycle-boundary` (point 19).
  - **Phase gates:** reject
    - `requiredBehavior` on a group, so every group is optional (point 11; added 2026-11-05, lifted in a later phase);
    - `timing` on a group;
    - `tolerance-days` on a group;
    - the `cycle-boundary` extension;
    - `fire-event` children of a group;
    - anything that follows a group (point 27).
- **Entities and repositories:**
  - a new `StepGroupInstance` entity and `StepGroupInstanceRepository`;
  - `StepInstance.stepGroupInstance`;
  - `StepInstanceRepository`: lookups by instance;
  - `ProtocolInstanceRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`).

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
-- Lookups by enrollment use the leading column of step_group_instance_cycle_key; no separate index.
CREATE INDEX idx_step_group_instance_parent ON step_group_instance (parent_group_instance_id)
    WHERE parent_group_instance_id IS NOT NULL;

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
  - **Opening a group instance:** insert the row, idempotently through the unique key. Then create its first members: its mandatory steps and nested groups with no predecessor inside it. An optional group is never opened in advance (point 11).
  - **Locking:** opening runs under the `protocol_instance` lock, taken only when the protocol has groups.
- `StepInstanceService`:
  - `createStep` takes the instance;
  - `createDependentSteps` deduplicates within a group instance when the step and its dependent share a group;
  - a dependent inside a group joins or opens its group's instance;
  - a dependent that is itself a group opens a group instance;
  - `detectOrderViolations`, `backfillMissingMandatorySteps` and the fan-in wait look a predecessor up by instance, not across the enrollment. Backfill creates a missing step in the shared instance (point 18);
  - a step created with a group that has no planned due date gets none, and no deadline rows (point 29).
- `MatcherEngine`:
  - `createInitialStep` attaches to, or opens, the instance and any unopened ancestors. A top-level group opens here (E2, scenario 2). Another event's transaction may have opened the group first. Then the open row that opening created for the matched step is used, not a second one.
  - A match on a group's action id is ignored.
  - `findActionableStep` takes the event's clinical time and picks the row by point 12. For a step in a group, it picks the group's latest cycle (F1). For a repeated step outside any group, it picks the open row with the nearest due date (B6). It orders the open rows itself, since the latest-cycle rule compares each row's chain of cycle numbers. This also fixes today's arbitrary pick between repeated steps.
  - An event for a step already completed in its instance adds a further completed row there (point 25).

**`cce-protocol-service`:** call `validateGroups` with the other load-time checks.

**Tests.**
- **Parser:** groups never appear in `extractSteps`, `mustStepIds` or the step graph. A step nested two groups deep is indexed and matched.
- **Load checks:** every rejection above.
- **Opening:** reactive and progressive opening; only the first members are created; deduplication within a group instance.
- **No completion:** a group instance whose mandatory steps are all done keeps `completed_at` and `closed_at` null.
- **No due date:** a step created with a group that has no planned due date has no due date and no deadline rows (point 29).
- **A further row:** a second event for a step already completed in its instance adds a further completed row there, and opens no second instance (point 25).
- **Row choice:** point 12's example.
- **Order violations and backfill:** a predecessor in a shared group is looked up in the shared instance (point 18).
- **Concurrency:** two first events for steps of one unopened group, processed at once, open one instance with one row per step (integration test).
- **Regression:** all existing suites pass unchanged.

**Done when:** protocols without groups behave exactly as before; one-off and nested groups load, open and attach steps.

### Phase 2: Visit-driven and nested repeats

**Scope.**
- Visit-driven repeats: the Rwanda journey, a `calendar-day` visit holding an `entry-step` Consultation. An event goes to the latest cycle (point 12).
- Still no completion or closure (F2). Deadlines stay on steps.

**Load checks** (`validateGroups` in `cce-common-util`, run by the protocol service at load)
- Accept the `cycle-boundary` extension, with `calendar-day` or `entry-step` (point 5).
- A `calendar-day` group inside another `calendar-day` group, at any depth, is rejected (point 23). Inside an `entry-step` group it is allowed.
- An `entry-step` group names its entry step in exactly one `cycle-entry-step` extension. The entry step is a step inside it, directly or in a nested group, with no predecessor inside it (point 5).

**Matcher.**
- **`calendar-day`:** a new cycle opens for an event whose clinical day is later than the latest cycle's day. The day is taken in the configured zone (point 4). Every other event goes to the latest cycle (points 6 and 12). Only an event opens a cycle. A predecessor completing, or the parent opening, opens none (point 26).
- **`entry-step`:** an event for any of its steps opens cycle 0 when there is none. Each event for the entry step starts the next cycle, unless the latest still awaits it (points 5 and 32). A cycle is opened only by an event, or by the completion of a step outside the group that the entry step follows (point 32). A parent opening opens none (point 26).
- **Where an event goes is decided before its row is looked for.** For a step in a group, this runs under the `protocol_instance` lock, from the top-level group down. At each level it takes the group's latest cycle inside its parent's, or a new cycle by the rules above. The event then completes the step's open row in that instance, or adds a further row there (point 25). Phase 1 looked for the open row first, outside the lock. On a new day it would complete the previous day's row instead of opening the new cycle.
- **A later cycle has no due date.** Cycle 0 of a visit-driven group takes its planned due date, as any group does. A cycle opened because the patient came back has none (design §7.1, "usually none"). Its predecessor's completion + offset would already be past. So the steps created with it, other than the matched one, are unjudged (point 29). The matched step is due when its event made it, as in phase 1.
- **Earlier cycles stay open.** Closing them as incomplete is E3, which comes with closure in phase 4 (F2).
- **Inner repeats:** numbered within their parent instance.
- **Instance lookups:** wherever the matcher adds to a group's instance, it takes the latest cycle, inside its parent's latest cycle, not cycle 0 (point 28).
- **Predecessors in a repeating group:** read in the group's latest cycle when they share no instance with the completed step (point 30).
- **Backfill follows group edges:** a mandatory predecessor declared on a group, or on an enclosing group, is backfilled when one of the group's steps is recorded first (point 31). The walk is `PlanDefinitionParser.computeMustPredecessorSteps` with the groups, in `cce-common-util`.

**Configuration:** a calendar-zone setting in the matcher, one per deployment (point 4), documented in its deployment guide.

**Tests.**
- The design's B2 cases.
- Point 6's backdated event goes to the latest visit.
- A check-in around midnight UTC.
- Two Consultations in one visit.
- A `calendar-day` group inside a visit is rejected at load. One inside an `entry-step` group loads (point 23).
- A `calendar-day` journey following registration opens no cycle until the first visit (point 26).
- A group's first step that follows a step outside the group is due by its own offset in cycle 0, waits while that step isn't done, and has no due date in a later cycle (point 29).
- A Consultation following check-in opens no cycle until an event for one of its steps (point 26).
- A repeated history opens a second Consultation; a repeated examination joins the current one (point 25).
- Point 18's example: an examination left undone in an earlier visit raises no order violation in a later one.
- Point 30's example: a prescription left undone in an earlier visit raises no order violation for a top-level treatment in a later one. A prescription missing from the later visit is backfilled into it.
- Point 31's example: a BP for visit 2 recorded before registration backfills registration, and so does a diet assessment in visit 2's nested nutrition group.
- A step that follows a step in the visit, and a backfilled step, join the latest visit (point 28).
- A BP before any history opens the Consultation's cycle 0 without history. The history that follows starts cycle 1 (point 26).
- An entry step that follows a step outside its group is created `NOT_STARTED` in cycle 0 when that step completes. Its first event completes it, and the next starts cycle 1 (point 32).
- An entry step in a nested group starts the enclosing group's next cycle, with fresh nested groups inside it (point 5).
- A `calendar-day` visit inside an `entry-step` pregnancy: each day is a visit in the latest pregnancy, and a new registration starts the next pregnancy, with its visits from cycle 0 (point 23).

### After phase 2: reporting for production

Taken up once phase 2 is implemented. Together with phases 1 and 2, it makes up the first production release (§4.4). Groups are neither complete nor closed before phase 4 (F2). So these reports show instances opened. Completion and closure reach the reports in phase 5.

**`cce-data-pipeline`**
- Add `public.step_group_instance` to `table.include.list` in `connectors/debezium-postgres-source.json`, and to the `cce_analytics_pub` publication in `cdc/01-configure-replication.sql`.
- ClickHouse tables and ingestion (`schema/01`, `02`) for the new table and the new columns from `V8`.
- Review `03`, `06`, `07` and `09`. Step-level counts must exclude group rows in `deviation` and `step_sla_state_transition` once phase 4 writes them. `07`'s daily summaries must allow a step with no `due_date` (point 29).
- ClickHouse views for group instances opened, per group, by facility and period (point 15).
- Rebuild.

**`cce-insights-service`**
- Audit every query on `deviation` and `step_sla_state_transition`. Each must read correctly once group rows (null step id) appear in phase 4. Inner joins to `step_instance` drop them. Counts over the whole table would include them.
- Audit every reader of `step_instance.due_date`. A step in a group can now leave it null (point 29). The readers include the patient timeline (`PatientTimelineService`) and `StepInstanceRepositoryImpl`.
- Group instance reports (point 15):
  - repository, service and controller endpoints for the counts above;
  - group instances on the patient timeline: visit cycles, and inner cycles under their parent instance.

**`cce-insights-ui`:** the group instance report, and group instances on the patient timeline.

**Tests.**
- Counts match the instances opened, nested groups included.
- Visits on two days, and two Consultations in one visit, show separately on the timeline.
- A step with no due date shows on the timeline as pending, without one.

### Phase 3: Scheduled repeats

**Scope.**
- Scheduled repeats: the quarterly review. Each cycle opens on its schedule (B4, point 20), and an event goes to the latest cycle (point 12).
- It follows the first production release (§4.4). Until it ships, a group with `timing` is refused at load.
- Still no completion or closure (F2). Deadlines stay on steps. A group's `tolerance-days` stays gated until phase 4.

**`cce-matcher-service`**
- **Migration `V9`:** an internal queue, `group_cycle_schedule`, with one row per scheduled cycle still to open. Its columns:
  - `step_group_instance_id`: the cycle it follows. It is unique, so a cycle is scheduled once;
  - `open_at`;
  - the same claim and retry columns as `step_sla_state_transition`.

  It isn't added to the analytics pipeline.
- **Scheduling the next cycle** (point 20): when a cycle opens, compute the next due date (points 1 and 2). Then apply the stop checks: `count`, then bounds (`boundsDuration` end excluded, `boundsPeriod.end` included). If the group goes on, write a `group_cycle_schedule` row. Its `open_at` is halfway between this cycle's due date and the next cycle's due date. If this cycle has no due date, its `opened_at` is used instead.
- **Cycle poller:** claims due `group_cycle_schedule` rows with `FOR UPDATE SKIP LOCKED`. It takes the `protocol_instance` lock and opens the next cycle. That cycle schedules the one after it. The poller drops the row instead when the enrollment isn't `ACTIVE` (point 24).
- **Monitoring:** the cycle poller logs an error once a row has failed repeatedly, as step-sla does for its transition rows. It also exposes how many rows are waiting. A stuck schedule row stops a schedule silently.
- **Row choice:** an event goes to the latest cycle, whatever its clinical date. With no open row there, it becomes a further row in it (points 12 and 25). Once the group has stopped, its last cycle keeps taking events (point 17).
- **Instance lookups:** phase 2's latest-cycle rule covers scheduled cycles too (point 28).

**`cce-insights-service`, `cce-insights-ui`:** scheduled cycles, with their due dates, on the patient timeline. This extends what was built after phase 2.

**`cce-common-util`:** `TimingInfo` gains `boundsDuration` and `boundsEnd`, parsed from `Timing.repeat.bounds[x]` (design §5.1), for the stop checks.

**Load checks** (`validateGroups` in `cce-common-util`, run by the protocol service at load)
- Lift the gate on group `timing`, with these checks:
  - the timing is a `Timing` with `period` and `periodUnit`;
  - it uses only fields CCE reads, and only supported units (design §5.3);
  - the group is top-level (point 23);
  - it has a `relatedAction` predecessor (E6);
  - it doesn't also have the `cycle-boundary` extension: a group has one or the other, never both (point 5).

**Tests.**
- **Scheduled repeats:**
  - the design's §7.5 examples table, row by row, `boundsDuration` and `boundsPeriod.end` included;
  - the next cycle opens halfway between due dates, whatever state the previous one is in (point 20);
  - events go to the latest cycle, whatever their clinical date (point 12);
  - point 2's fallback;
  - point 17;
  - a withdrawn enrollment opens no further cycle (point 24);
  - a cycle is scheduled once;
  - a nested scheduled group is rejected at load (point 23).
- **Instance lookups:** with two cycles open, two kinds of row join the latest cycle (point 28): a step following a step in another group, and a backfilled step.
- **Timeline:** two open scheduled cycles show separately, each with its due date.
- **Monitoring:** a row that keeps failing is logged as an error.
- **Concurrency:** the cycle poller and an event for the same enrollment at once (integration test).

### Phase 4: Completion, closure and group deadlines

**Scope.**
- Group completion: `completed_at`, and `closed_at` with it, since a complete group is closed (F2 moved it here).
- Closure without completion: from members' verdicts, from E3, and from a group's own `MISSED`.
- What follows a group: it opens when the group completes or closes (E2, scenario 1). This lifts point 27's gate.
- The hand-off from step-sla to the matcher (B5).
- Group deadlines and their verdicts, and group deviations.
- Completion and closure for instances already open in production, recorded once at release.

**`cce-common-util`**
- **Shared completion logic,** `GroupCompletion`: decides "complete" over a set of members and reads `selectionBehavior`. Built so protocol completion can reuse it (D3).
- **Parser helper:** a group's mandatory members (A3-aware).
- The `SlaHandoff` entity and repository, used by the matcher and step-sla.
- **Entities:** the group reference on `StepSlaStateTransition`, `Deviation` and `IntelligenceEventLog`, with their step reference made optional. `V8` already holds the columns (D1). This phase is the first to write group rows to those tables.
- A group variant of `DeviationRecorder`.

**`cce-matcher-service`**
- **Migration `V10`:** an internal hand-off table, `sla_handoff`, with one row per deviation the matcher must act on:
  - `deviation_id`, not null and unique. There is one hand-off per deviation, and that is what makes writing it idempotent. The step or group it concerns is read from the deviation;
  - `protocol_instance_id`, for the lock;
  - the same claim and retry columns as `step_sla_state_transition`.

  It is an internal queue, so it isn't added to the analytics pipeline.
- **Completion:** after each step completes, recompute completion for the step's instance and its ancestors, and set `completed_at` and `closed_at`. Optional groups never count (point 11). A repeating member is done once any of its cycles in the instance is complete (point 21).
- **Closure** (design §7.4):
  - settled members;
  - members never created because a predecessor was written off;
  - a repeating member is settled once every cycle opened in the instance is closed (point 21).

  Closure is recomputed after every step completion and for every hand-off row. Set `closed_at` (point 22), then open what follows.
- **E3:** opening a visit-driven cycle closes the earlier open cycles of the same group, as incomplete. For `calendar-day`, these are the cycles for earlier days. For `entry-step`, it is the previous cycle. Their `closed_at` is the new cycle's `opened_at` (point 22).
- **Closing an incomplete instance** also closes its open nested instances, repeating or not. Each gets the parent's `closed_at`. No new inner cycles open in it.
- **Late work in a closed instance** (A2, design §7.4): the step still completes. If that completes the instance, `completed_at` is set. `closed_at` and `sla_status` don't change, and no dependents are created inside it.
- **Order violations:** a step in a closed instance is no longer outstanding (point 18).
- **What follows a group:** opens when the group completes or closes. "After-end", the only supported relationship, counts from its `completed_at`, or its `closed_at` if it closed without completing (point 7).
- **E5 fill-in** happens when a predecessor completes, or when a predecessor that is a group closes (point 8). It fills in:
  - the group's due date;
  - the due dates and deadline rows of the steps created with it that have none;
  - the group's own deadline rows.
- **Hand-off poller:** claims `sla_handoff` rows with `FOR UPDATE SKIP LOCKED` and takes the `protocol_instance` lock. It then recomputes closure and opens what follows. It is monitored as the cycle poller is (phase 3). A stuck hand-off row leaves a group open.
- **`StepSlaScheduleService`:**
  - when a group with `tolerance-days` opens with a known due date, write its `DUE_DATE_REACHED` and `MISSED_DATE_REACHED` rows, for every cycle of a scheduled group;
  - when E5's fill-in sets a group's due date, write those rows then, under point 8's conditions;
  - write no SLA rows for steps under a group deadline;
  - write the group's `MET_CONDITION_REACHED` row when it completes on time.
- **A group's own `MISSED`** closes it, together with its open nested instances. Its `closed_at` is the group's missed date (point 22).
- **At release:** a one-off pass runs under each enrollment's lock. It records completion and closure for the instances opened before this phase. It also fills in due dates (E5) for those opened early whose predecessor has since completed. None of these had the logic, and the events that would trigger it have already happened.

**`cce-step-sla-service`**
- `SlaTransitionFetchRepository`: fetch group rows, locking the group row.
- `SlaTransitionApplier`:
  - **judging:** judge a group row by comparing the group's `completed_at` with the row's threshold, as for steps;
  - **writing:** write `step_group_instance.sla_status` through `SlaStatus.canReplace`. Record the group deviation, with its metadata listing the member step rows not completed (A1);
  - **hand-off:** when it writes `MISSED` on a step in a group or on a group, write an `sla_handoff` row for the deviation it records. This happens in the same transaction.
- Step-sla never takes the `protocol_instance` lock.

**Load checks** (`validateGroups` in `cce-common-util`, run by the protocol service at load)
- Lift the gates on group `tolerance-days`, and on anything that follows a group (point 27).
- Warn at load for a step deadline under a group deadline (C1) and for nested group deadlines (point 10).
- Reject a deadline on a top-level group with no predecessor (point 9).

**Tests.**
- **Completion:**
  - nested groups;
  - an optional group, started or not, never holds its parent back;
  - a visit with one complete Consultation of two is complete once its other mandatory members are (point 21).
- **Closure:**
  - by write-off, opening what follows with the point 7 due date;
  - by the last unsettled member completing after the others were written off;
  - late work after closure sets `completed_at` only.
- **E3:** opening a visit closes the previous one, and its open Consultation with it.
- **What follows a group:** after-end, from a completed group and from a closed one.
- **E5:**
  - fill-in on a predecessor's completion and on a group predecessor's closure, with the steps created with the group getting their due dates and deadline rows;
  - a group's deadline rows are written when its due date is filled in;
  - point 8's edge cases.
- **The ANC scenarios** (design A1):
  - early arrival is `MET`;
  - late arrival is one `OVERDUE`;
  - never coming is `OVERDUE` + `MISSED`;
  - came but incomplete is `MISSED`.
- **SLA rows:** no step SLA rows under a group deadline.
- **Scheduled cycles:** a group deadline on a scheduled cycle is judged per cycle.
- **Group write-off:** a group's `MISSED` closes it and its open nested groups, and opens what follows.
- **Hand-off:** writing it twice for one deviation leaves one row.
- **Release pass:** instances completed, or written off, before the release are recorded as complete or closed. A group instance opened early whose predecessor completed before the release gets its due dates.
- **Concurrency:** two members completing at once set `completed_at` once; a matcher-versus-step-sla race on one database (integration tests).

### Phase 5: Group completion and verdict reporting

Starts once the rest of point 15 is answered. It adds completion, closure and group verdicts to the reports built after phase 2. Intelligence on group deviations comes in phase 6.

**Changes.**
- **ClickHouse:** views for instances completed and closed without completing. Also views for group verdicts, which are the group's `sla_status` (A2).
- **`cce-insights-service`:** endpoints for those counts, and for `MET` / `OVERDUE` / `MISSED` per group, by facility and period.
- **`cce-insights-ui`:** completion, closure and verdicts in the group reports and on the patient timeline.

**Tests.**
- A cycle closed by write-off, and a visit closed by E3, count as closed without completing.

### Phase 6: Intelligence on deviations

**Scope:** E4, D4 and point 13, for step and group deviations.

**Changes.**
- **Step-sla:** writes an `sla_handoff` row for every deviation it records: `OVERDUE` or `MISSED`, step or group. Phase 4 hands off only the `MISSED` ones. No migration.
- **Matcher:** the hand-off poller evaluates the intelligence actions for each row's deviation. For a step it uses the existing `IntelligenceActionEvaluator.evaluateOnDeviation`. For a group it uses a new group variant. It still recomputes closure for a `MISSED` in or on a group.
- **Rule context:** gains `groupActionId` and `cycleIndex` for steps inside groups, in both the deviation and the completion context.
- **Event log:** `intelligence_event_log` rows for group events have `step_status` null.
- **Load checks:** lift the gate on `fire-event` children of a group, in `validateGroups` (`cce-common-util`).
- **Not retroactive:** deviations recorded before this phase ships are never evaluated. The release notes say so.

**Tests.**
- A group `MISSED` fires the group's action.
- Step `OVERDUE` / `MISSED` fires the step's action, for a step in a group and for one outside any group.
- The rule context includes the cycle number, on a deviation and on a completion.

---

## 4. Cross-cutting

### 4.1 Concurrency

- **Lock:** every group decision (opening, scheduling the next cycle and, from phase 4, completion and closure) runs under the `protocol_instance` lock, taken only when the protocol has groups.
- **Lock order in the matcher:**
  - for a step in a group: `protocol_instance` first, then the step row, since the row is chosen under the lock (from phase 2);
  - for a step outside any group: the step row first, when completing it, then `protocol_instance`.

  The two orders can't deadlock. While holding `protocol_instance`, a transaction updates only the row of a step in a group. No transaction locks such a row before `protocol_instance`. Group rows are written only while holding the protocol lock.
- **Step-sla:** it never takes the protocol lock. It claims the step or group row it judges with `SKIP LOCKED`. It writes the verdict, the deviation and any hand-off row. It leaves every group decision to the matcher. It never waits on a lock the matcher holds, so the two can't deadlock.

### 4.2 Documentation

Each phase updates the documentation it changes:
- **`cce-common-util/docs/data-dictionary.md`:** the new table and columns, and column ownership.
- **Architecture overviews:** for the matcher, step-sla, protocol service and common-util.
- **Deployment guides:**
  - `V8`, `V9` and `V10`;
  - the cycle and hand-off pollers' settings, and what their errors and backlog mean;
  - the calendar-zone setting.
- **A sample PlanDefinition** with groups.

### 4.3 Operations: Event Replay (C3)

Add to the Event Replay sections of the matcher and step-sla deployment guides:
- **From phase 3, during a replay:** stop the matcher's cycle poller. Its rows wait, and open their cycles once it restarts. Otherwise a cycle can open while older events are still being replayed. Those events then go to it (F1, point 12). The runbook also says that a replayed event goes to its group's latest cycle, whatever its clinical date.
- **From phase 4, during a replay:** stop step-sla and the matcher's hand-off poller too.
- **From phase 4, afterwards:** list for review:
  - the group instances closed during the replay window without completing (`closed_at` in the window, `completed_at` null);
  - anything opened because they closed (point 7).

  Verdicts can't be undone, so the list is what operators act on.

### 4.4 Rollout

Phases 1 and 2 are all required for the first production deployment, together with the reporting for them. The reporting's data-pipeline, insights-service and insights-ui updates are taken up once phase 2 is implemented. Before that deployment, phases 1 and 2 go to test environments only. After it, each phase is one production release, starting with scheduled repeats (phase 3). Each release covers the repos it touches, deployed in this order:
1. `cce-common-util`;
2. the matcher, including its migration;
3. every other service built on the new `cce-common-util`, such as `cce-collector-service`. It validates its entity mappings against the schema at startup (`ddl-auto: validate`), so it fails to start without the migration;
4. the data pipeline, after the migration it reads from, then insights-service and insights-ui;
5. step-sla;
6. the protocol service last.

The load-time gates live in `cce-common-util`, but only the protocol service runs them. Deploying it last means a lifted gate takes effect only once every other service already supports the feature. Phase 6's step intelligence has no gate (§5). Phase 4's completion and closure reach instances already open through its release pass.

---

## 5. Risks

| Risk | Mitigation |
|---|---|
| A group is treated as a step somewhere, since it is typed `step` | Phase 1 parser tests; ignore matches on group action ids. |
| Group rows in `deviation` / `step_sla_state_transition` distort step-level numbers | Audit of insights and pipeline queries after phase 2, before phase 4 writes any. |
| Cross-service races (matcher versus step-sla) | Integration tests with both services on one database, from phase 4. |
| Phase 6 switches on intelligence for step `OVERDUE` / `MISSED` in protocols already loaded | Before release, list loaded protocols with `fire-event` actions on those deviations, and include them in the release notes. |
| Calendar-day cycles split at the wrong time | Point 4's zone setting; tests around midnight. |
| An event goes to the latest cycle whatever its clinical date (F1). So work sent late for an earlier cycle is credited to the latest one. The earlier cycle's step can be written off although it was done | Agreed with the customer. The release notes say so, and reports show each row in the cycle it was credited to. |
| Phase 4 brings completion and closure to instances open in production since phases 1–3 | The release pass records them; test it on a copy of production data before release. |
| Point 15 is still open when phases 1 and 2 are built, holding up the production release | Settle its instance part with product before the reporting work after phase 2 starts. |
| `V8` adds columns to tables the pipeline already replicates, long before the pipeline is updated after phase 2 | When phase 1 reaches a test environment with the pipeline running, check that ingestion still runs with the new columns. If it doesn't, bring that ingestion fix forward. |
