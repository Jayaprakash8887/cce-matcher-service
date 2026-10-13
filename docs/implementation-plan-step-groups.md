# Implementation plan: Step groups

**Status:** Draft for review.
**Based on:** [design-repeating-step-groups.md](design-repeating-step-groups.md) (the design), the decisions in §1, and the points in §2 with their status, as of 2026-10-13. Question ids (A1–E6) are the design's.
**Baseline:** CCE 2.0.0 (`release-2.0.0` of `cce-common-util`, `cce-matcher-service`, `cce-step-sla-service`, `cce-protocol-service`, `cce-data-pipeline`, `cce-insights-service`).

**Approach.** Phase 1 builds the whole data model; later phases add behaviour. A phase that adds something an author writes in a PlanDefinition is switched on by lifting a load-time check in the protocol service. Until that phase ships, a PlanDefinition that uses its feature is refused at load, so no feature can be half-active in production. Two kinds of behaviour have no such switch: phase 2's closure of one-off groups, which reaches production only with phases 1–3, and intelligence on step `OVERDUE` / `MISSED` (phase 6), which applies to protocols already loaded (§5). Phases 1–3 are all required before production, together with the data-pipeline, insights-service and insights-ui updates for them, which are taken up after phase 3 (§4.4). Protocols without groups behave exactly as today throughout.

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
| B4 | Open on schedule | The next scheduled cycle opens on its schedule, whether or not the previous one has completed or closed (point 20). Its due date follows a fixed calendar (points 1 and 2). This replaces the design's proposal, open on closure. |
| B5 | (a) Hand-off row | Step-sla writes a row that the matcher drains. Only the matcher creates steps and schedules deadlines. |
| B6 | (a) Nearest due date | `findActionableStep` picks the open row whose due date is nearest the event's clinical time. |
| C1 | (b) Warn | A step deadline under a group deadline is logged as a warning at load. The group's deadline applies; the step's `tolerance-days` is ignored. |
| C2 | Dropped (was (a)) | No load check. A mandatory step under a scheduled group may have no `tolerance-days`: it then goes `OVERDUE` but is never written off, so its cycle stays open, and under B4 the next cycle opens anyway (point 20). |
| C3 | (b) Runbook step | The Event Replay runbook gains a group-specific step (§4.3). |
| D1 | (a) Nullable reference | `step_sla_state_transition`, `deviation` and `intelligence_event_log` gain `step_group_instance_id`. Exactly one of the step and group references is set. |
| D2 | Calculate | Only facts are stored: `opened_at`, `completed_at`, `closed_at`, `sla_status`. |
| D3 | Deferred | Not in this release. Protocol instances stay `ACTIVE`, as in 2.0.0 today. The group completion logic is written so protocol completion can reuse it later. |
| D4 | As proposed | The rule context gains `groupActionId` and `cycleIndex`. |
| D5 | Yes | Group reporting in insights: occurrences in the first production release (after phase 3), verdicts in phase 5. Scope: point 15. |
| D7 | `step_group_instance` | Table and column names as in the design. |
| E1 | `all` only | `selectionBehavior` absent or `all`; other values rejected at load. |
| E2 | Scenario 1 (b), scenario 2 (b) | A group whose predecessor is a group opens when that predecessor closes. A top-level group with no predecessor opens when one of its steps is first matched. |
| E3 | (b) | Opening a visit's cycle closes the previous visit's cycle, as incomplete. |
| E4 | Yes, on deviations | A group's `fire-event` actions fire on the group's deviations. The matcher evaluates them through a hand-off from step-sla, which also switches on intelligence for step `OVERDUE` / `MISSED` (point 13). |
| E5 | (b) Fill in later | A group opened before its due date can be worked out gets the due date, and its deadline rows, when its predecessor completes, or closes if the predecessor is a group (point 8). |
| E6 | (a) Require a predecessor | A scheduled repeating group, always top-level (point 23), must have a `relatedAction` predecessor, checked at load. |

---

## 2. Points the decisions left open

The decisions in §1 left some cases open: places where two decisions conflicted, or where a decision didn't cover a case. This section lists them as points 1–26, each with an example, its resolution and the phase it affects. Each resolution is labelled with its status:
- ***Decided:*** settled by an explicit decision.
- ***Accepted proposal:*** an engineering detail whose proposal stands unless a reviewer objects.
- ***Open:*** no answer yet.

| # | Point | Status | Phase |
|---|---|---|---|
| 1 | When the next scheduled cycle is due | Decided | 2 |
| 2 | A scheduled cycle with nothing to count from | Decided | 2 |
| 3 | The deadline rule (C2) would reject the Rwanda journey | Fell away: C2 is dropped | — |
| 4 | Which time zone a calendar day is in | Decided | 3 |
| 5 | How a group says what starts a new cycle | Decided | 3 |
| 6 | Late events dated to an earlier day | Accepted proposal | 3 |
| 7 | The due date of a group opened because its predecessor closed | Accepted proposal | 2 |
| 8 | Edge cases of filling in a due date later (E5) | Accepted proposal | 2, 4 |
| 9 | A deadline on a top-level group with no predecessor | Accepted proposal | 4 |
| 10 | Nested groups that both have deadlines | Accepted proposal | 4 |
| 11 | Optional groups and completion | Decided | 1 |
| 12 | Which open row an event completes, in detail | Accepted proposal | 1 |
| 13 | Intelligence on `OVERDUE` / `MISSED` deviations | Decided | 6 |
| 14 | Protocol completion rules | Fell away: protocol completion is deferred | — |
| 15 | Which group reports | Open | after 3, 5 |
| 16 | `step_status` on group intelligence events | Accepted proposal | 1 |
| 17 | Events after a scheduled group has stopped | Accepted proposal | 2 |
| 18 | Order violations and backfill across cycles | Accepted proposal | 1, 2 |
| 19 | A deadline on a visit-driven group | Decided | 1 |
| 20 | When the next scheduled cycle opens | Accepted proposal | 2 |
| 21 | A repeating group as a member of its parent | Decided | 1–3 |
| 22 | `closed_at` when an occurrence closes without completing | Accepted proposal | 2–4 |
| 23 | Which repeating groups may nest inside which | Decided | 2, 3 |
| 24 | Withdrawn enrollments | Decided | 2 |
| 25 | An event for a step already completed in its occurrence | Accepted proposal | 1–3 |
| 26 | What opens a `calendar-day` cycle | Decided | 3 |

**1. When is the next scheduled cycle due?** *(B4; blocks phase 2)*
B4 settles when the next cycle opens (point 20), but the design left its due date open. Example: quarterly review, cycle 0 due 10 Jan, its work finished on 25 Feb. Cycle 1 is due either 10 Apr (fixed calendar) or 25 May (3 months after the work).
*Decided:* fixed calendar, the rule single-step repeats already follow.

**2. A scheduled cycle with nothing to count from.** *(E6 + E5 + B4; blocks phase 2)*
E6 requires a predecessor so that cycle 0 has a due date. But a group still opens early if one of its steps is matched before the predecessor completes. Its due date then waits for E5's fill-in, and if the predecessor never completes, it never gets one. Example: a quarterly review due "1 week after diagnosis". The patient's first vitals arrive on 10 Jan, and the diagnosis is never recorded. When cycle 1 is to open, the fixed calendar has nothing to count from, and `boundsDuration` has no start.
*Decided:* when cycle 0 has no due date, count the cadence and `boundsDuration` from its `opened_at`. A starting point for counting is not a deadline, so cycle 0 still isn't judged late.

**3. C2 would reject the Rwanda journey.** *(C2 + E3)*
No longer needed now: C2 is dropped (§1). Visit-driven cycles close through E3, and scheduled ones open on schedule whatever state the previous cycle is in (point 20).

**4. Calendar day in which time zone?** *(B2; blocks phase 3)*
Event times are stored in UTC. A check-in at 23:30 UTC on 3 Mar is 01:30 on 4 Mar in Kigali, so the time zone decides which visit it belongs to.
*Decided:* a deployment setting naming the zone (`Africa/Kigali` for Rwanda, `Africa/Nairobi` for Kenya), used by every calendar-day group in that deployment.

**5. A repeating inner group can't be separated by calendar day.** *(B1 + B2 + B3; blocks phase 3)*
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

**6. Late events for an earlier day.** *(B2 + E3; blocks phase 3)*
The patient visited on 3 Mar and 17 Mar. Vitals dated 10 Mar arrive on 18 Mar, after the 17 Mar cycle opened. No cycle exists for 10 Mar. Opening one would, under E3, close the still-open 17 Mar cycle. It would also get cycle number 2, after the 17 Mar cycle's 1.
*Accepted proposal:*
- a calendar-day cycle's day is not stored. CCE keeps clinical event time in UTC, and the cycle is opened by the first event of its day, so its day is `opened_at`'s date in the deployment's time zone (point 4). One cycle per day is held by the enrollment lock (§4.1); no index can hold it, since the time zone is a deployment setting;
- `cycle_index` numbers cycles in the order they were opened, not by day;
- opening a cycle closes only open cycles for earlier days, and a cycle opened for a day earlier than an existing one is created already closed;
- an event for a day whose cycle is closed is recorded in that cycle, and creates no follow-on steps there.

**7. The due date of a group opened because its predecessor closed.** *(E2; blocks phase 2)*
ANC visit 3 is due "6 weeks after visit 2". Visit 2 is written off at 22 weeks without completing. Visit 3 opens (E2), but there is no completion to count 6 weeks from.
*Accepted proposal:* count from the predecessor's `closed_at`, here the write-off time. This applies to anything, step or group, that follows a group. Anything that follows a step keeps today's rule: it is created only when the step completes.

**8. E5 fill-in edge cases.** *(E5; blocks phases 2 and 4)*
- **The group has already finished.** If the predecessor completes after the group is already complete or closed, a deadline filled in afterwards would judge finished work. *Accepted proposal:* fill in only while the group is open and not complete.
- **Steps created with the group.** Steps created when the group opened take the group's due date. While that is unknown they get none, and so no deadline either: they can't go `OVERDUE` or be written off. *Accepted proposal:* fill in those steps' due dates and deadlines at the same moment, for steps that still have none. This bends the write-once rule for step due dates as well as group ones.
- **A predecessor that is a group.** A group can close without completing (point 7), so a follower opened early would never get its due date. *Accepted proposal:* fill in when a group predecessor closes too, counting from its `closed_at`.
- **Two halves.** Phase 2 fills in the due dates (the group's and its steps') and the steps' deadline rows, so a group opened early still closes. Phase 4 writes the group's own deadline rows at that moment.

**9. A deadline on a top-level group with no predecessor can never apply.** *(E2 + E5; blocks phase 4)*
Such a group opens when one of its steps is first matched (E2), so it has no planned due date. With no predecessor, E5 never fills one in, and its `tolerance-days` would never schedule anything.
*Accepted proposal:* reject it at load, as the protocol service already rejects a deadline on an optional step.

**10. Nested groups that both have deadlines.** *(C1; blocks phase 4)*
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

**13. Nothing evaluates intelligence on `OVERDUE` or `MISSED` today.** *(E4; blocks phase 6)*
Group deviations are `OVERDUE` and `MISSED`, recorded by step-sla. Step-sla doesn't evaluate intelligence; its code says "Evaluating and publishing intelligence on a deviation is not part of this service yet". In 2.0.0 only `ORDER_VIOLATION`, recorded by the matcher, fires intelligence. A step's `fire-event` action conditioned on `MISSED` doesn't fire either.
*Decided:*
- **Step-sla** judges group deadlines and records group deviations (phase 4). For each deviation it records, step or group, it writes a hand-off row, the same mechanism as B5.
- **The matcher** drains those rows and evaluates the intelligence actions. Step-sla stays free of Kafka and PlanDefinitions.
- **Steps too:** following E4 (intelligence is driven by deviations, step or group), this also switches on intelligence for step `OVERDUE` / `MISSED` (phase 6). That changes behaviour for protocols already loaded that have `fire-event` actions on those deviations.
- **Groups fire on deviations only,** not on completion as steps do.

**14. Protocol completion rules.** *(D3)*
No longer needed now: protocol completion is deferred (§1, D3). The three rules (what counts as completed, open-ended groups, re-enrollment after completion) return when it is built.

**15. Which group reports?** *(D5; blocks the reporting after phase 3, and phase 5)*
*Open.* A minimal first set is proposed, to confirm with product. The occurrence part is built after phase 3, for the first production release; the verdict part needs phase 4's group deadlines:
- per group, by facility and period: occurrences opened, completed, and closed without completing (after phase 3);
- group occurrences, with their cycle numbers, on the patient timeline (after phase 3);
- per group: `MET` / `OVERDUE` / `MISSED` (phase 5).

**16. `intelligence_event_log.step_status` for group events.** *(D1; blocks phase 1)*
The column is `NOT NULL`, and a group has no `step_status`.
*Accepted proposal:* make it nullable.

**17. Events after a scheduled group has stopped.** *(B4; blocks phase 2)*
A quarterly review with `count: 4` has had its four cycles, and the last has closed. A fifth set of vitals arrives. Opening a fifth cycle would go past what the protocol asked for.
*Accepted proposal:* the step is recorded, with no group, so the work is kept but no new cycle opens. This is the rule in the design's §7.3. While the last cycle is still open, it takes the event instead (point 25).

**18. Order violations and backfill across cycles.** *(B1 + B4 + E3; blocks phases 1 and 2)*
`detectOrderViolations`, `backfillMissingMandatorySteps` and the fan-in wait in `createDependentSteps` look a predecessor up by its action id across the whole enrollment. Once a group repeats, one cycle sees another's rows. Say a prescription follows an examination in the Rwanda journey. On 3 Mar the examination isn't recorded, and the visit closes under E3 with it still `NOT_STARTED`. On 17 Mar both are recorded in order, but the 3 Mar row is still outstanding, so the prescription raises an `ORDER_VIOLATION`. The other way round, a predecessor missing from the 17 Mar visit isn't backfilled, because the 3 Mar visit has a row for it.
*Accepted proposal:*
- a predecessor in the same group as the completed step, or in one of its enclosing groups, is looked up in that shared occurrence, and a missing one is backfilled into it; any other is looked up across the enrollment, as today (phase 1);
- a step in a closed occurrence is not outstanding (phase 2).

**19. A deadline on a visit-driven group.** *(B3 + E3; blocks phase 1)*
A `cycle-boundary` cycle opens when the patient comes back, so it usually has no planned due date (design §4.3), and its `tolerance-days` would rarely, if ever, schedule anything.
*Decided:* reject `tolerance-days` on a group with `cycle-boundary`, as point 9 does for a top-level group with no predecessor. It is a permanent check from phase 1.

**20. When the next scheduled cycle opens.** *(B4; blocks phase 2)*
B4 opens a scheduled cycle on its schedule, whatever state the previous one is in, but not at what moment. Example: quarterly review, cycle 0 due 10 Jan, cycle 1 due 10 Apr. Cycle 1 has to exist before 10 Apr, so that its steps are pending and can be judged.
*Accepted proposal:*
- cycle N+1 opens when cycle N's due date is reached (10 Jan here), or at once if that has passed. Each cycle then opens one period before its own due date, as a dependent step is created an offset before its due date today. A cycle 0 with no due date counts from its `opened_at` (point 2), so cycle 1 opens with it;
- opening the next cycle doesn't close the previous one. Each closes by its own rules, unlike a visit (E3). An event goes to the open cycle whose row is nearest its clinical time (B6);
- the stop checks (`count`, bounds) run when the next cycle is scheduled, so a group that has stopped schedules nothing more (point 17);
- if E5 later fills in cycle 0's due date, cycles already opened keep theirs.

*What this changes in the design* (which is kept unchanged as the reviewed record):
- **§7.5, step 1:** "the next cycle opens when cycle N closes, or when it completes" no longer holds; the rules above replace it. Steps 2–5 stand, with the stop checks run when the next cycle is scheduled, and the fixed-calendar examples still hold.
- **B4:** the design proposed open on closure. In its scenario, cycle 1 now opens on 10 Jan, when cycle 0 falls due, and is due 10 Apr. The reviews carry on whether cycle 0's HbA1c is done late, written off, or never settled.
- **B5:** a verdict no longer opens the next scheduled cycle. The hand-off is still needed: a write-off can still close an occurrence, and what follows a closed group opens then (point 7), as ANC visit 3 does after visit 2 is written off.
- **C2:** its scenario, where an HbA1c that can never be written off stops the reviews, assumed open on closure. Under open on schedule the reviews go on, and only that cycle stays open. C2 is dropped (§1).

**21. A repeating group as a member of its parent.** *(B1 + B4; blocks phases 1–3)*
A Rwanda visit holds a Consultation that can repeat within the visit (point 5). The visit is complete when its mandatory members are done, but nothing said when a member that repeats is done, or settled.
*Decided:*
- done once any of its cycles in the parent occurrence is complete. A visit with two Consultations, one complete, is complete once its other mandatory members are. Phase 1's `StepGroupService` already counts it this way;
- settled once every cycle opened in the parent occurrence is closed.

Only visit-driven groups can repeat inside a parent: a scheduled group is always top-level (point 23).

**22. `closed_at` when an occurrence closes without completing.** *(D2 + E3; blocks phases 2–4)*
Point 7 counts what follows a closed group from its `closed_at`. If `closed_at` were the moment the hand-off poller happened to run, those due dates would shift with poll timing.
*Accepted proposal:* `closed_at` is always a clinical or planned time, never a processing time:
- closed through its members (phase 2): the latest time a mandatory member settled, which is a completion's clinical time, a write-off's missed date, or a nested group's `closed_at`;
- written off by its own deadline (phase 4): the group's missed date;
- closed by E3 (phase 3): the `opened_at` of the cycle whose opening closed it;
- closed with its parent (phase 3): the parent's `closed_at`;
- created already closed (point 6): its own `opened_at`.

A complete occurrence's `closed_at` stays its `completed_at`.

**23. Which repeating groups may nest inside which.** *(B1 + B3 + B4; blocks phases 2 and 3)*
Point 5 gives an inner group `entry-step`, but nothing stopped a `calendar-day` group inside a visit, where every event falls on the same day, or a scheduled group inside any group. A scheduled group inside a visit would restart its schedule with every visit. Inside a one-off group it would clash with point 21: the parent would be complete, and so closed, once one review cycle was done, and no further cycle could open inside it.
*Decided:*
- a scheduled group is always top-level; nested, it is rejected at load. No current protocol needs one;
- a repeating group inside another repeating group uses `cycle-boundary` `entry-step`; a `calendar-day` group there is rejected at load. A visit-driven group inside a one-off group is allowed.

**24. Withdrawn enrollments.** *(B4; blocks phase 2)*
`ProtocolInstanceStatus` has `WITHDRAWN`. Point 20's schedule would keep opening cycles for a withdrawn patient, each with its own deadlines and `MISSED` deviations, and an open-ended one would never stop.
*Decided:* the cycle poller drops the schedule rows of an enrollment that isn't `ACTIVE`.

**25. An event for a step already completed in its occurrence.** *(B6; blocks phases 1–3)*
A second set of vitals arrives during a quarterly cycle whose vitals row is already completed. No open row awaits it (B6), so it is recorded as a new step.
*Accepted proposal:* it becomes a further completed row, as a repeated event outside a group adds a row today. It changes nothing about completion and creates no step the occurrence already has. The occurrence that takes it:
- a one-off group: its single occurrence, even once complete and closed; nothing is created inside a closed one (A2);
- a scheduled group: the open cycle whose due date is nearest the event's clinical time (phase 2);
- an `entry-step` group: a repeated entry step opens a new cycle instead (point 5); any other step joins the current cycle (phase 3);
- a `calendar-day` group: the cycle for the event's day (point 12).

**26. What opens a `calendar-day` cycle.** *(B2 + E2; blocks phase 3)*
Phase 1 opens a group in advance when its predecessor completes, or when its parent opens. For a `calendar-day` journey that follows registration, that would open a cycle on the registration day (point 6). If the first visit comes on another day, it opens a new cycle and E3 closes the first one, empty and incomplete, and reports show a visit that never happened.
*Decided:* only an event for one of its steps opens a `calendar-day` cycle. A predecessor completing, or its parent opening, opens none. An `entry-step` group, such as the Consultation inside a visit, still opens in advance as other groups do.

---

## 3. Phases

| Phase | Delivers | Needs | Repos |
|---|---|---|---|
| 1. Foundation | One-off and nested groups, completion, all schema changes, B6 row ordering | A3, B6, D1, D2, D7, E1, E2 (scenario 2); points 11, 12, 16, 18, 19, 21, 25 | common-util, matcher, protocol |
| 2. Closure and scheduled repeats | Closure, what follows a closed group, the hand-off, E5's due-date fill-in, the quarterly review | B4, B5, E2 (scenario 1), E5 (due dates), E6; points 1, 2, 7, 8, 17, 18, 20–25 | common-util, matcher, step-sla, protocol |
| 3. Visit-driven and nested repeats | The Rwanda journey; closing nested occurrences with their parent | B1, B2, B3, E3; points 4–6, 18, 21–23, 25, 26 | common-util, matcher, protocol |
| After 3. Reporting for production | The pipeline for the new table and columns; occurrence and cycle reports; the patient timeline | D5; point 15 | pipeline, insights, insights-ui |
| 4. Group deadlines | Group verdicts and deviations, closure by a group's write-off | A1, C1, E5 (deadline rows); points 8–10, 22 | common-util, matcher, step-sla, protocol |
| 5. Group verdict reporting | Group `MET` / `OVERDUE` / `MISSED` in reports, without intelligence | A2, D5; point 15 | pipeline, insights, insights-ui |
| 6. Intelligence on deviations | Intelligence on step and group `OVERDUE` / `MISSED`; rule context | E4, D4; point 13 | common-util, matcher, step-sla, protocol |

**Order.** Phases 1–3, and the reporting for them after phase 3, are the first production release (§4.4). Phase 1 can start now. Every later phase needs phase 1, and phases 3, 4 and 6 need phase 2, for closure and the hand-off. Phase 5 needs 4, for group verdicts to report; phase 6 needs 4, for group deviations. Phase 4 comes after phase 3, which is in the first production release, and reuses its closing of nested occurrences. Phases 5 and 6 are independent too: phase 5 waits on point 15, so phase 6 can ship first if that is still open. Protocol completion (D3) is not part of this plan.

### Phase 1: Foundation

**Scope.**
- Groups that occur once, nested to any depth, with steps attached to their group's occurrence.
- Completion: `completed_at` is set, and `closed_at` with it, since a complete group is closed.
- Deadlines stay on steps exactly as today.
- All schema changes for every phase, except the internal queues (phase 2), so the analytics pipeline changes once, after phase 3.

**`cce-common-util`**
- `PlanDefinitionParser`:
  - **New records and fields:** a `GroupMetadata` record (design §5.1); `StepMetadata.groupId`; `TimingInfo` bounds fields (parsed now, used in phase 2).
  - **Groups kept apart:** `flattenAction` branches on `groupingBehavior: logical-group`, so a group never becomes a `StepMetadata`.
  - **Dependency graph:** it accepts groups as nodes.
  - **Helpers:** a step's group path; a group's mandatory members (A3-aware); its entry members.
- Update the 36 `StepMetadata` constructor calls across `cce-common-util`, `cce-matcher-service` and `cce-protocol-service` (mostly tests).
- `ParsedProtocolCache.ParsedProtocol` gains `groups()` and `group(id)`.
- `validateGroups(PlanDefinition)`:
  - **Permanent checks:** a group has no trigger; contains at least one step, directly or nested; `logical-group` only on `step`-typed actions; `selectionBehavior` absent or `all`; no `tolerance-days` on an optional group; no `tolerance-days` on a group with `cycle-boundary` (point 19).
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
  - **Opening an occurrence:** insert the row, idempotently through the unique key, then create its mandatory entry steps and open its entry nested groups. An optional group is never opened in advance (point 11).
  - **After each step completes:** recompute completion for the step's occurrence and its ancestors, set `completed_at` and `closed_at`, and open whatever follows a completed group.
  - **Locking:** both run under the `protocol_instance` lock, taken only when the protocol has groups.
- `StepInstanceService`:
  - `createStep` takes the occurrence;
  - `createDependentSteps` deduplicates within an occurrence when the step and its dependent share a group;
  - a dependent inside a group joins or opens its group's occurrence;
  - a dependent that is itself a group opens an occurrence;
  - "after-start" from a group counts from its `due_date`, or `opened_at` if it has none;
  - `detectOrderViolations`, `backfillMissingMandatorySteps` and the fan-in wait look a predecessor up by occurrence, not across the enrollment, and backfill creates a missing step in the shared occurrence (point 18).
- `MatcherEngine`:
  - `createInitialStep` attaches to, or opens, the occurrence and any unopened ancestors. A top-level group opens here (E2, scenario 2).
  - A match on a group's action id is ignored.
  - An event for a step already completed in its occurrence adds a further completed row there (point 25).
  - `findActionableStep` takes the event's clinical time and orders by B6 and point 12. This also fixes today's arbitrary pick between repeated steps.

**`cce-protocol-service`:** call `validateGroups` with the other load-time checks.

**Tests.**
- **Parser:** groups never appear in `extractSteps`, `mustStepIds` or the step graph; a step nested two groups deep is indexed and matched.
- **Load checks:** every rejection above.
- **Opening:** reactive and progressive opening; entry steps only; deduplication within an occurrence.
- **Completion:** nested groups; an optional group, started or not, never holds its parent back.
- **Ordering:** the B6 example in the design.
- **Order violations and backfill:** a predecessor in a shared group is looked up in the shared occurrence (point 18).
- **Concurrency:** two members completing at once set `completed_at` once (integration test).
- **Regression:** all existing suites pass unchanged.

**Done when:** protocols without groups behave exactly as before; one-off and nested groups load, open, attach steps and complete.

### Phase 2: Closure and scheduled repeats

**Scope.**
- Closure without completion, decided from step verdicts. This is where closure is built.
- What follows a group also opens when the group closes without completing (E2, scenario 1).
- E5's fill-in of due dates.
- The hand-off from step-sla to the matcher, for a grouped step written off.
- Scheduled repeats: the quarterly review. Each cycle opens on its schedule, not on the previous cycle's closure (B4, point 20).
- Deadlines stay on steps; a group's `tolerance-days` stays gated until phase 4.

**`cce-matcher-service`**
- **Migration `V9`:** an internal hand-off table, `sla_handoff`, with one row per deviation the matcher must act on:
  - `deviation_id`, not null and unique: one hand-off per deviation, which is what makes writing it idempotent. The step or group it concerns is read from the deviation;
  - `protocol_instance_id`, for the lock;
  - the same claim and retry columns as `step_sla_state_transition`.

  And `group_cycle_schedule`: one row per scheduled cycle still to open, with `step_group_instance_id` (the cycle it follows; unique, so a cycle is scheduled once), `open_at`, and the same claim and retry columns. Both are internal queues, so neither is added to the analytics pipeline.
- **Closure** (design §7.4): settled members; members never created because a predecessor was written off; optional groups never hold their parent open (point 11); a repeating member is settled once every cycle opened in this occurrence is closed (point 21). Recomputed after every step completion and for every hand-off row. Set `closed_at` (point 22), then open what follows.
- **Late work in a closed occurrence** (A2, design §7.4): the step still completes, and if that completes the occurrence, `completed_at` is set. `closed_at` and `sla_status` don't change, and no dependents are created inside it.
- **Order violations:** a step in a closed occurrence is no longer outstanding (point 18).
- **What follows a closed group:** opens when the group closes without completing, and "after-end" counts from its `closed_at` (point 7).
- **E5 fill-in** when a predecessor completes, or when a predecessor that is a group closes (point 8): the group's due date, and the due dates and deadline rows of the steps created with it that have none. The group's own deadline rows wait for phase 4.
- **Hand-off poller:** claims `sla_handoff` rows with `FOR UPDATE SKIP LOCKED`, takes the `protocol_instance` lock, recomputes closure, and opens what follows.
- **Scheduling the next cycle** (point 20): when a cycle opens, compute the next due date (points 1 and 2) and apply the stop checks: `count`, then bounds (`boundsDuration` end excluded, `boundsPeriod.end` included). If the group goes on, write a `group_cycle_schedule` row with `open_at` set to this cycle's due date, or its `opened_at` if it has none.
- **Cycle poller:** claims due `group_cycle_schedule` rows with `FOR UPDATE SKIP LOCKED`, takes the `protocol_instance` lock, and opens the next cycle, which schedules the one after it. It drops the row instead when the enrollment isn't `ACTIVE` (point 24).
- **Monitoring:** both pollers log an error once a row has failed repeatedly, as step-sla does for its transition rows, and expose how many rows are waiting. A stuck hand-off row leaves a group open; a stuck schedule row stops a schedule silently.
- **After the group stops:** a step event that arrives once the group has stopped and its last cycle has closed is recorded with no group (point 17).
- **An event with no open row** goes to the open cycle whose due date is nearest its clinical time (point 25).

**`cce-step-sla-service`**
- `SlaTransitionApplier`: when it writes `MISSED` on a step in a group, write an `sla_handoff` row for the deviation it records, in the same transaction.
- Step-sla never takes the `protocol_instance` lock.

**`cce-common-util`:** the `SlaHandoff` entity and repository, used by both services.

**`cce-protocol-service`**
- Lift the gate on group `timing`, with these load checks:
  - the timing is a `Timing` with `period` and `periodUnit`;
  - it uses only fields CCE reads, and only supported units (design §5.3);
  - the group is top-level (point 23);
  - it has a `relatedAction` predecessor (E6).

**Tests.**
- **Closure:** by write-off, opening what follows with the point 7 due date; by the last unsettled member completing after the others were written off; late work after closure sets `completed_at` only.
- **What follows a group:** after-end from a closed group (point 7).
- **E5:** fill-in on a predecessor's completion and on a group predecessor's closure, and point 8's edge cases.
- **Scheduled repeats:** the design's §7.5 examples table, row by row; the next cycle opens on schedule whether the previous one is complete, closed or still open; two open cycles, with events going to the nearest (B6); point 2's fallback; point 17; a withdrawn enrollment opens no further cycle (point 24); a cycle is scheduled once; a nested scheduled group is rejected at load (point 23).
- **Order violations:** a step in a closed cycle doesn't count as outstanding (point 18).
- **Hand-off:** writing it twice for one deviation leaves one row.
- **Monitoring:** a row that keeps failing is logged as an error.
- **Concurrency:** a matcher-versus-step-sla race on one database (integration test).

### Phase 3: Visit-driven and nested repeats

**Load checks.**
- Accept the `cycle-boundary` extension, with `calendar-day` or `entry-step` (point 5).
- A group has either `timingTiming.repeat` or `cycle-boundary`, not both.
- A repeating group inside another repeating group uses `entry-step`; a `calendar-day` group there is rejected (point 23).

**Matcher.**
- **`calendar-day`:** the event's clinical day, in the configured zone (point 4), selects the occurrence. Earlier-day events follow point 6. Only an event opens a cycle; a predecessor completing, or the parent opening, opens none (point 26).
- **`entry-step`:** a new cycle opens when an entry step repeats after it's already completed in the current cycle (point 5).
- **E3:** opening a cycle closes the earlier open cycles of the same group, as incomplete: for `calendar-day`, those for earlier days (point 6); for `entry-step`, the previous cycle. Its `closed_at` is the new cycle's `opened_at` (point 22).
- **Inner repeats:** numbered within their parent occurrence.
- **Closing an incomplete occurrence:** closing an occurrence that isn't complete (here by E3; from phase 4 by a group's `MISSED`) also closes its open nested occurrences, repeating or not, each with the parent's `closed_at`, and no new inner cycles open in it.

**Configuration:** a calendar-zone setting in the matcher, one per deployment (point 4), documented in its deployment guide.

**Tests.**
- The design's B2 cases.
- Point 6's backdated event.
- A check-in around midnight UTC.
- Two Consultations in one visit.
- Closing a visit closes its open Consultation.
- A `calendar-day` group inside a visit is rejected at load.
- A `calendar-day` journey following registration opens no cycle until the first visit (point 26).
- A repeated history opens a second Consultation; a repeated examination joins the current one (point 25).
- Point 18's example: an examination left undone in an earlier visit raises no order violation in a later one.

### After phase 3: reporting for production

Taken up once phase 3 is implemented. Together with phases 1–3, it makes up the first production release (§4.4).

**`cce-data-pipeline`**
- Add `public.step_group_instance` to `table.include.list` in `connectors/debezium-postgres-source.json`, and to the `cce_analytics_pub` publication in `cdc/01-configure-replication.sql`.
- ClickHouse tables and ingestion (`schema/01`, `02`) for the new table and the new columns from `V8`.
- Review `03`, `06`, `07` and `09`. Step-level counts must exclude group rows in `deviation` and `step_sla_state_transition` once phase 4 writes them.
- ClickHouse views for group occurrences (point 15): opened, completed and closed without completing, per group, by facility and period.
- Rebuild.

**`cce-insights-service`**
- Audit every query on `deviation` and `step_sla_state_transition`. Each must read correctly once group rows (null step id) appear in phase 4: inner joins to `step_instance` drop them, while counts over the whole table would include them.
- Group occurrence reports (point 15): repository, service and controller endpoints for the counts above, and group occurrences on the patient timeline: scheduled cycles with their due dates, visit cycles, and inner cycles under their parent occurrence.

**`cce-insights-ui`:** the group occurrence report, and group occurrences on the patient timeline.

**Tests.**
- Counts match the occurrences opened, completed and closed without completing, nested groups included.
- A cycle closed by write-off, and a visit closed by E3, count as closed without completing.
- Two open scheduled cycles, and two Consultations in one visit, show separately on the timeline.

### Phase 4: Group deadlines

**Scope.**
- Group deadlines and their verdicts.
- Group deviations.
- A group written off by its own `MISSED` closes.

**`cce-matcher-service`**
- **`StepSlaScheduleService`:**
  - when a group with `tolerance-days` opens with a known due date, write its `DUE_DATE_REACHED` and `MISSED_DATE_REACHED` rows, for every cycle of a scheduled group;
  - when phase 2's E5 fill-in sets a group's due date, write those rows then, under point 8's conditions;
  - write no SLA rows for steps under a group deadline;
  - write the group's `MET_CONDITION_REACHED` row when it completes on time.
- **Closure:** a group written off by its own `MISSED` closes, together with its open nested occurrences (phase 3). Its `closed_at` is the group's missed date (point 22). The hand-off poller drains the row step-sla writes for it.

**`cce-step-sla-service`**
- `SlaTransitionFetchRepository`: fetch group rows, locking the group row.
- `SlaTransitionApplier`:
  - **judging:** judge a group row by comparing the group's `completed_at` with the row's threshold, as for steps;
  - **writing:** write `step_group_instance.sla_status` through `SlaStatus.canReplace`, and record the group deviation, its metadata listing the member step rows not completed (A1);
  - **hand-off:** when it writes `MISSED` on a group, write an `sla_handoff` row for the group deviation, in the same transaction.

**`cce-common-util`:** a group variant of `DeviationRecorder`.

**`cce-protocol-service`**
- Lift the gate on group `tolerance-days`.
- Warn at load for a step deadline under a group deadline (C1) and for nested group deadlines (point 10).
- Reject a deadline on a top-level group with no predecessor (point 9).

**Tests.**
- **The ANC scenarios** (design A1): early arrival is `MET`; late arrival is one `OVERDUE`; never coming is `OVERDUE` + `MISSED`; came but incomplete is `MISSED`.
- **SLA rows:** no step SLA rows under a group deadline.
- **E5:** a group's deadline rows are written when its due date is filled in.
- **Scheduled cycles:** a group deadline on a scheduled cycle is judged per cycle.
- **Closure:** a group's `MISSED` closes it and its open nested groups, and opens what follows.
- **Concurrency:** a matcher-versus-step-sla race on a group verdict (integration test).

### Phase 5: Group verdict reporting

Starts once the verdict part of point 15 is answered. It adds group verdicts to the reports built after phase 3; intelligence on group deviations comes in phase 6.

**Changes.**
- **ClickHouse:** views for group verdicts, the group's `sla_status` (A2).
- **`cce-insights-service`:** endpoints for `MET` / `OVERDUE` / `MISSED` per group, by facility and period.
- **`cce-insights-ui`:** verdicts in the group reports and on the patient timeline.

### Phase 6: Intelligence on deviations

**Scope:** E4, D4 and point 13, for step and group deviations.

**Changes.**
- **Step-sla:** writes an `sla_handoff` row for every deviation it records, `OVERDUE` or `MISSED`, step or group, not only the `MISSED` ones phases 2 and 4 hand off. No migration.
- **Matcher:** the hand-off poller evaluates the intelligence actions for each row's deviation: the existing `IntelligenceActionEvaluator.evaluateOnDeviation` for a step, and a new group variant for a group. It still recomputes closure for a `MISSED` in or on a group.
- **Rule context:** gains `groupActionId` and `cycleIndex` for steps inside groups, in both the deviation and the completion context.
- **Event log:** `intelligence_event_log` rows for group events have `step_status` null.
- **Protocol service:** lift the gate on `fire-event` children of a group.
- **Not retroactive:** deviations recorded before this phase ships are never evaluated. The release notes say so.

**Tests.**
- A group `MISSED` fires the group's action.
- Step `OVERDUE` / `MISSED` fires the step's action, for a step in a group and for one outside any group.
- The rule context includes the cycle number, on a deviation and on a completion.

---

## 4. Cross-cutting

### 4.1 Concurrency

- **Lock:** every group decision (completion, closure, opening, scheduling the next cycle) runs under the `protocol_instance` lock, taken only when the protocol has groups.
- **Lock order in the matcher:** the step row first, when completing a step, then `protocol_instance`. Group rows are written only while holding the protocol lock.
- **Step-sla:** it never takes the protocol lock. It claims the step or group row it judges with `SKIP LOCKED`, writes the verdict, the deviation and any hand-off row, and leaves every group decision to the matcher. It never waits on a lock the matcher holds, so the two can't deadlock.

### 4.2 Documentation

Each phase updates the documentation it changes:
- **`cce-common-util/docs/data-dictionary.md`:** the new table and columns, and column ownership.
- **Architecture overviews:** for the matcher, step-sla, protocol service and common-util.
- **Deployment guides:** `V8` and `V9`, the hand-off and cycle pollers' settings and what their errors and backlog mean, and the calendar-zone setting.
- **A sample PlanDefinition** with groups.

### 4.3 Operations: Event Replay (C3)

Add to the Event Replay sections of the matcher and step-sla deployment guides:
- **During a replay:** stop step-sla and the matcher's hand-off poller.
- **Afterwards:** list the group occurrences closed during the replay window without completing (`closed_at` in the window, `completed_at` null), and anything opened because they closed (point 7), for review. Verdicts can't be undone, so the list is what operators act on.

### 4.4 Rollout

Phases 1–3 are all required for the first production deployment, together with the reporting for them, whose data-pipeline, insights-service and insights-ui updates are taken up once phase 3 is implemented. Before that deployment, phases 1–3 go to test environments only; after it, each phase is one production release. Each release covers the repos it touches, deployed in this order:
1. `cce-common-util`;
2. the matcher, including its migration;
3. every other service built on the new `cce-common-util`, such as `cce-collector-service`: it validates its entity mappings against the schema at startup (`ddl-auto: validate`), so it fails to start without the migration;
4. the data pipeline, after the migration it reads from, then insights-service and insights-ui;
5. step-sla;
6. the protocol service last.

Because the protocol service lifts the load-time gate last, no protocol can use a feature before the services support it. Phase 6's step intelligence has no gate (§5).

---

## 5. Risks

| Risk | Mitigation |
|---|---|
| A group is treated as a step somewhere, since it is typed `step` | Phase 1 parser tests; ignore matches on group action ids. |
| Group rows in `deviation` / `step_sla_state_transition` distort step-level numbers | Audit of insights and pipeline queries after phase 3, before phase 4 writes any. |
| Cross-service races (matcher versus step-sla) | Integration tests with both services on one database, from phase 2. |
| Phase 6 switches on intelligence for step `OVERDUE` / `MISSED` in protocols already loaded | Before release, list loaded protocols with `fire-event` actions on those deviations, and include them in the release notes. |
| Calendar-day cycles split at the wrong time | Point 4's zone setting; tests around midnight. |
| Point 15 is still open when phases 1–3 are built, holding up the production release | Settle its occurrence part with product before the reporting work after phase 3 starts. |
| `V8` adds columns to tables the pipeline already replicates, long before the pipeline is updated after phase 3 | When phase 1 reaches a test environment with the pipeline running, check that ingestion still runs with the new columns; if it doesn't, bring that ingestion fix forward. |
