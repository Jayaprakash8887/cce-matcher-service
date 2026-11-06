package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.CycleBoundary;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.repository.StepGroupInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step group instances: which instance of a group an event or a piece of work goes to, and opening one
 * with the steps it holds from the start.
 *
 * <p>Every method here expects the caller to hold the enrollment's lock
 * ({@link ProtocolInstanceService#lockProtocolInstance}) and to have read the enrollment's step instances
 * under it (see {@link Enrollment}). Opening an instance adds the step instances it creates there.
 */
@Service
@Transactional
public class StepGroupService {

    private static final Logger log = LoggerFactory.getLogger(StepGroupService.class);

    /** The cycle index of a group's first instance within its parent's. */
    private static final int FIRST_CYCLE = 0;

    private final StepGroupInstanceRepository groupInstanceRepository;
    private final StepCreationService stepCreationService;
    private final StepInstanceScope stepInstanceScope;
    private final ZoneId calendarZone;

    /**
     * @param calendarZone the deployment's time zone, in which a {@code calendar-day} group's days are
     *                     counted. One per deployment: clinical times are stored in UTC, and a check-in at
     *                     23:30 UTC is already the next day in Kigali.
     */
    public StepGroupService(StepGroupInstanceRepository groupInstanceRepository,
                            StepCreationService stepCreationService,
                            StepInstanceScope stepInstanceScope,
                            @Value("${cce.step-groups.calendar-zone:UTC}") ZoneId calendarZone) {
        this.groupInstanceRepository = groupInstanceRepository;
        this.stepCreationService = stepCreationService;
        this.stepInstanceScope = stepInstanceScope;
        this.calendarZone = calendarZone;
    }

    // ── Which instance ──

    /**
     * The instance of {@code matchedStepMetadata}'s group that an event for it goes to, in two passes:
     * <ol>
     *   <li>Find the groups around the step: its own group, then each parent out to the top-level group.</li>
     *   <li>Choose their instances from the outermost in. A group's instances live inside one instance of
     *       its parent, so the parent's has to be chosen first; and a trigger on an outer group gives the
     *       groups inside it fresh instances. A patient seen on day 1 comes back on day 15 for an
     *       examination: the journey opens visit 2 for the new day, and the consultation starts again in
     *       visit 2, rather than the examination going to visit 1's consultation.</li>
     * </ol>
     *
     * @param occurredAt the event's clinical time: which day it falls on, and the opening time of any
     *                   instance it opens
     */
    public StepGroupInstance groupInstanceForEvent(Enrollment enrollment,
                                                   PlanDefinitionParser.StepMetadata matchedStepMetadata,
                                                   OffsetDateTime occurredAt) {
        MatchedEventMetadata matchedEventMetadata = new MatchedEventMetadata(matchedStepMetadata, occurredAt);

        // 1. The groups around the step, from its own group out to the top level.
        List<PlanDefinitionParser.GroupMetadata> groupsAroundStep =
                enrollment.groupsFromInnermostOut(matchedStepMetadata.groupId());

        // 2. Their instances, from the outermost in: each group's is chosen inside its parent's.
        StepGroupInstance parentGroupInstance = null; // a top-level group's instances belong to the enrollment
        StepGroupInstance groupInstance = null;
        for (PlanDefinitionParser.GroupMetadata groupMetadata : groupsAroundStep.reversed()) {
            groupInstance = groupInstanceForEventWithin(parentGroupInstance, enrollment, groupMetadata, matchedEventMetadata);
            parentGroupInstance = groupInstance; // the next group in is looked up inside this instance
        }
        return groupInstance;
    }

    /**
     * The instance of one group, inside {@code parentGroupInstance}, that an event goes to: the group's
     * latest instance, unless the event brings a trigger (see {@link #opensNewGroupInstance}).
     */
    private StepGroupInstance groupInstanceForEventWithin(StepGroupInstance parentGroupInstance,
                                                          Enrollment enrollment,
                                                          PlanDefinitionParser.GroupMetadata groupMetadata,
                                                          MatchedEventMetadata matchedEventMetadata) {
        // Fetch latest group Instance inside a parent group instance
        Optional<StepGroupInstance> latestGroupInstance =
                latestGroupInstance(enrollment, groupMetadata.id(), parentGroupInstance);
        boolean opensNewGroupInstance = opensNewGroupInstance(enrollment, groupMetadata, latestGroupInstance,
                Optional.of(matchedEventMetadata), null);
        if (opensNewGroupInstance) {
            // A trigger fired: the event opens the group's next instance.
            return openNextGroupInstance(enrollment, groupMetadata, parentGroupInstance, latestGroupInstance,
                    matchedEventMetadata.occurredAt(), matchedEventMetadata.matchedStepMetadata().id());
        }
        // No trigger: the event joins the latest instance.
        return latestGroupInstance.orElseThrow();
    }

    /**
     * The instance of {@code groupId} for work that isn't an event of the group's own: a step following a
     * step in another group, a backfilled step, a group following a step. The same two passes as for an
     * event (see {@link #groupInstanceForEvent}): find the groups from {@code groupId} outwards, then choose
     * their instances from the outermost in.
     *
     * <p>Empty when a group on the way that repeats by visit has no instance yet: only an event for one of
     * its steps opens one, since its cycles start when the patient comes. The exception is work for an
     * {@code entry-step} group's entry step (see {@link #onlyAnEventOpens}).
     *
     * @param anchor     the instance of the step whose completion the work comes from, or null
     * @param openedAt   the clinical time of whatever needs the instance
     * @param workStepId the step that follows a completed step, which the work creates in the group, or null
     *                   for other work: a backfilled step or a group following a step
     */
    public Optional<StepGroupInstance> groupInstanceForWork(Enrollment enrollment, String groupId,
                                                            StepGroupInstance anchor, OffsetDateTime openedAt,
                                                            String workStepId) {
        // 1. The groups around the work, from its own group out to the top level.
        List<PlanDefinitionParser.GroupMetadata> groupsAroundWork = enrollment.groupsFromInnermostOut(groupId);

        // 2. Their instances, from the outermost in: each group's is chosen inside its parent's.
        StepGroupInstance parentGroupInstance = null; // a top-level group's instances belong to the enrollment
        StepGroupInstance groupInstance = null;
        for (PlanDefinitionParser.GroupMetadata groupMetadata : groupsAroundWork.reversed()) {
            Optional<StepGroupInstance> groupInstanceForWork =
                    groupInstanceForWorkWithin(parentGroupInstance, enrollment, groupMetadata, anchor, openedAt,
                            workStepId);
            if (groupInstanceForWork.isEmpty()) {
                return Optional.empty(); // a group that repeats by visit, with no instance yet
            }
            groupInstance = groupInstanceForWork.get();
            parentGroupInstance = groupInstance; // the next group in is looked up inside this instance
        }
        return Optional.of(groupInstance);
    }

    /**
     * The instance of one group, inside {@code parentGroupInstance}, that work goes to: the instance around
     * {@code anchor}, else the group's latest, else a first one (see {@link #opensNewGroupInstance}). Empty
     * for a group that repeats by visit with no instance yet.
     */
    private Optional<StepGroupInstance> groupInstanceForWorkWithin(StepGroupInstance parentGroupInstance,
                                                                   Enrollment enrollment,
                                                                   PlanDefinitionParser.GroupMetadata groupMetadata,
                                                                   StepGroupInstance anchor,
                                                                   OffsetDateTime openedAt,
                                                                   String workStepId) {
        StepGroupInstance groupInstanceAroundAnchor = enclosingGroupInstance(anchor, groupMetadata.id());
        if (groupInstanceAroundAnchor != null) {
            // The work stays in the cycle that gave rise to it, even when a later cycle has opened.
            return Optional.of(groupInstanceAroundAnchor);
        }
        Optional<StepGroupInstance> latestGroupInstance =
                latestGroupInstance(enrollment, groupMetadata.id(), parentGroupInstance);
        boolean opensNewGroupInstance =
                opensNewGroupInstance(enrollment, groupMetadata, latestGroupInstance, Optional.empty(), workStepId);
        if (opensNewGroupInstance) {
            // None yet: work opens the group's first instance.
            return Optional.of(openNextGroupInstance(enrollment, groupMetadata, parentGroupInstance,
                    latestGroupInstance, openedAt, null));
        }
        // An instance exists: work joins the latest. Empty for a group that repeats by visit with none yet.
        return latestGroupInstance;
    }

    /**
     * A group's latest instance within one instance of its parent, or within the enrollment for a top-level
     * group. The parent is an instance, not a group: a nested group's cycles are numbered within each
     * instance of its parent, so each visit's Consultation has its own cycle 0.
     */
    private Optional<StepGroupInstance> latestGroupInstance(Enrollment enrollment, String groupId,
                                                            StepGroupInstance parentGroupInstance) {
        return groupInstanceRepository.findLatestCycle(enrollment.protocolInstance().getId(), groupId,
                parentGroupInstance == null ? null : parentGroupInstance.getId());
    }

    /** {@code fromGroupInstance} or the instance around it that belongs to {@code groupId}, or null. */
    private static StepGroupInstance enclosingGroupInstance(StepGroupInstance fromGroupInstance, String groupId) {
        for (StepGroupInstance groupInstance = fromGroupInstance; groupInstance != null;
             groupInstance = groupInstance.getParentGroupInstance()) {
            if (groupId.equals(groupInstance.getGroupActionId())) {
                return groupInstance;
            }
        }
        return null;
    }

    // ── When a group gets a new instance ──

    /**
     * Whether the group {@code groupMetadata} describes gets a new instance within its parent's, rather
     * than its latest one. There are three triggers, the same at every level of nesting:
     * <ol>
     *   <li><b>None yet:</b> the group has no instance. Only an event opens the first instance of a group
     *       that repeats by visit, except work for an {@code entry-step} group's entry step (see
     *       {@link #onlyAnEventOpens}).</li>
     *   <li><b>New calendar day:</b> for a {@code calendar-day} group, an event on a later day than the
     *       latest instance's.</li>
     *   <li><b>Entry step:</b> for an {@code entry-step} group, an event for its entry step, unless the latest
     *       instance still awaits its entry step, which the event then completes.</li>
     * </ol>
     * Work that isn't an event only ever brings the first: otherwise it joins the latest instance.
     *
     * @param matchedEventMetadata the event for one of the group's steps, or empty when other work needs
     *                             the instance
     * @param workStepId           for work, the step that follows a completed step, or null
     */
    private boolean opensNewGroupInstance(Enrollment enrollment, PlanDefinitionParser.GroupMetadata groupMetadata,
                                          Optional<StepGroupInstance> latestGroupInstance,
                                          Optional<MatchedEventMetadata> matchedEventMetadata, String workStepId) {
        if (latestGroupInstance.isEmpty()) { // 1. none yet
            return matchedEventMetadata.isPresent() || !onlyAnEventOpens(groupMetadata, workStepId);
        }
        if (matchedEventMetadata.isEmpty()) {
            return false; // triggers 2 and 3 come only with an event: work joins the latest instance
        }
        if (groupMetadata.cycleBoundary() == CycleBoundary.CALENDAR_DAY) { // 2. new calendar day
            return isOnLaterDay(latestGroupInstance.get(), matchedEventMetadata.get().occurredAt());
        }
        if (groupMetadata.cycleBoundary() == CycleBoundary.ENTRY_STEP) { // 3. entry step
            boolean isEntryStep =
                    matchedEventMetadata.get().matchedStepMetadata().id().equals(groupMetadata.entryStepId());
            return isEntryStep && !awaitsEntryStep(enrollment, groupMetadata, latestGroupInstance.get());
        }
        return false; // a group that doesn't repeat by visit: every event joins its latest instance
    }

    /**
     * Whether only an event for one of the group's steps can open an instance of it, rather than work for
     * {@code workStepId}. True of a group that repeats by visit, since its cycles start when the patient
     * comes: a {@code calendar-day} group's on the day of an event, an {@code entry-step} group's with an
     * event for one of its steps. Neither a predecessor completing nor the parent opening opens one.
     *
     * <p>The exception is work for an {@code entry-step} group's entry step: the entry step follows a step
     * outside the group ({@code relatedAction}), so the protocol expects it, and that step's completion opens
     * cycle 0 with the entry step awaiting, due by its own offset.
     *
     * @param workStepId the step that follows a completed step, which the work creates in the group, or null
     */
    private static boolean onlyAnEventOpens(PlanDefinitionParser.GroupMetadata groupMetadata, String workStepId) {
        return switch (groupMetadata.cycleBoundary()) {
            case CALENDAR_DAY -> true;
            case ENTRY_STEP -> workStepId == null || !workStepId.equals(groupMetadata.entryStepId());
            case null -> false;
        };
    }

    /**
     * Whether {@code groupInstance} has a step instance of the group's entry step still awaiting its event,
     * in the instance itself or in a nested group's instance inside it.
     */
    private static boolean awaitsEntryStep(Enrollment enrollment, PlanDefinitionParser.GroupMetadata groupMetadata,
                                           StepGroupInstance groupInstance) {
        return enrollment.stepInstancesOf(groupMetadata.entryStepId()).stream()
                .anyMatch(stepInstance -> stepInstance.getStepStatus() == StepStatus.NOT_STARTED
                        && StepInstanceScope.isInside(stepInstance.getStepGroupInstance(), groupInstance));
    }

    /**
     * Whether {@code stepMetadata} is the entry step of its own group or of a group around it. Opening a group
     * instance never creates such a step: its own event creates it, or the work that expects it.
     */
    private static boolean isAnEntryStep(Enrollment enrollment, PlanDefinitionParser.StepMetadata stepMetadata) {
        return enrollment.groupsFromInnermostOut(stepMetadata.groupId()).stream()
                .anyMatch(groupMetadata -> stepMetadata.id().equals(groupMetadata.entryStepId()));
    }

    /**
     * Whether {@code at} falls on a later calendar day, in the deployment's time zone, than the day
     * {@code groupInstance} opened. An instance's day is not stored: the first event of its day opened it.
     */
    private boolean isOnLaterDay(StepGroupInstance groupInstance, OffsetDateTime at) {
        return at.atZoneSameInstant(calendarZone).toLocalDate()
                .isAfter(groupInstance.getOpenedAt().atZoneSameInstant(calendarZone).toLocalDate());
    }

    /** An event for a step in a group: the step it matched, and the event's clinical time. */
    private record MatchedEventMetadata(PlanDefinitionParser.StepMetadata matchedStepMetadata, OffsetDateTime occurredAt) {
    }

    // ── Opening ──

    /**
     * Open the first instance of the group {@code groupMetadata} describes, which follows
     * {@code completedStepInstance}, now that it has completed. An optional group isn't opened by a step it
     * follows: it opens for an event for one of its steps, or for work for one of its {@code must} steps (see
     * {@link #groupInstanceForWork}). Nor is a group that repeats by visit, whose cycles start when the
     * patient comes (see {@link #onlyAnEventOpens}). A group with other
     * prerequisites still in flight waits for them; each of their completions runs this again.
     */
    public void openFollowingGroup(Enrollment enrollment, StepInstance completedStepInstance,
                                   PlanDefinitionParser.GroupMetadata groupMetadata) {
        if (!groupMetadata.isMandatory() || onlyAnEventOpens(groupMetadata, null)) {
            return; // optional groups, and groups that repeat by visit, open only for an event of their own
        }
        StepGroupInstance parentGroupInstance = null;
        if (groupMetadata.parentGroupId() != null) {
            // A nested group opens inside its parent's instance: the one around the completed step when there
            // is one, otherwise the parent's latest.
            Optional<StepGroupInstance> parentForWork = groupInstanceForWork(enrollment,
                    groupMetadata.parentGroupId(), completedStepInstance.getStepGroupInstance(),
                    completedStepInstance.getCompletedAt(), null);
            if (parentForWork.isEmpty()) {
                return; // inside a group that repeats by visit, with no instance yet
            }
            parentGroupInstance = parentForWork.get();
        }
        if (latestGroupInstance(enrollment, groupMetadata.id(), parentGroupInstance).isPresent()) {
            return; // already open
        }
        PlannedDueDate plannedDueDate = plannedDueDate(enrollment, groupMetadata, parentGroupInstance);
        if (plannedDueDate.waits()) {
            // Its due date counts from its last prerequisite to finish, and another is still in flight;
            // that one's completion runs this again.
            log.debug("Deferring group {} — another prerequisite is still in flight (instanceId={})",
                    groupMetadata.id(), enrollment.protocolInstance().getId());
            return;
        }
        // The step it follows has just been done, so its offset is still ahead, even in a later cycle of the
        // parent: unlike opening with the parent, the planned due date always stands here.
        openGroupInstance(enrollment, groupMetadata, parentGroupInstance, FIRST_CYCLE, plannedDueDate.dueDate(),
                completedStepInstance.getCompletedAt(), null);
    }

    /**
     * Open the instance of the group that comes after {@code latestGroupInstance} within
     * {@code parentGroupInstance}. The first, cycle 0, is due when the protocol plans it (see
     * {@link #plannedDueDate}). A later one opens because the patient came back, and has no due date: its
     * predecessor's completion plus offset would already be past.
     *
     * @param matchedStepId a step being created by an event, which opening must not create with the
     *                      others; null when there is none
     */
    private StepGroupInstance openNextGroupInstance(Enrollment enrollment,
                                                    PlanDefinitionParser.GroupMetadata groupMetadata,
                                                    StepGroupInstance parentGroupInstance,
                                                    Optional<StepGroupInstance> latestGroupInstance,
                                                    OffsetDateTime openedAt, String matchedStepId) {
        if (latestGroupInstance.isEmpty()) {
            // The first instance: due when the protocol plans it. Inside a later cycle of a group around it, a
            // step it follows was done before that cycle, so its offset is already past: it has no due date,
            // like a first step there (see firstStepDueDate).
            OffsetDateTime dueDate = followsAStepFromAnEarlierCycle(enrollment, groupMetadata, parentGroupInstance)
                    ? null
                    : plannedDueDate(enrollment, groupMetadata, parentGroupInstance).dueDate();
            return openGroupInstance(enrollment, groupMetadata, parentGroupInstance, FIRST_CYCLE,
                    dueDate, openedAt, matchedStepId);
        }
        // A later cycle: the patient came back, past any date the protocol could plan, so it has none.
        return openGroupInstance(enrollment, groupMetadata, parentGroupInstance,
                latestGroupInstance.get().getCycleIndex() + 1, null, openedAt, matchedStepId);
    }

    /**
     * Open an instance of a group and create what it holds from the start: its mandatory members with
     * no predecessor inside the group.
     * <ul>
     *   <li>A step is due at the instance's due date, or by its own offset when it follows a step outside the
     *       group (see {@link #firstStepDueDate}).</li>
     *   <li>Nested groups open their first instance, except a group that repeats by visit, which only an event
     *       opens.</li>
     *   <li>An {@code entry-step} group's entry step, in the group or a nested group, is never created here
     *       (see {@link #isAnEntryStep}): a cycle opened by another of its steps doesn't await it.</li>
     * </ul>
     * Later members arrive through progressive instantiation, with the due dates their own
     * {@code relatedAction} offsets give them. The caller has checked that this cycle doesn't exist.
     *
     * @param matchedStepId a step being created by an event, which opening must not create with the
     *                      others; null when there is none
     */
    private StepGroupInstance openGroupInstance(Enrollment enrollment,
                                                PlanDefinitionParser.GroupMetadata groupMetadata,
                                                StepGroupInstance parentGroupInstance, int cycleIndex,
                                                OffsetDateTime dueDate, OffsetDateTime openedAt,
                                                String matchedStepId) {
        StepGroupInstance groupInstance = insert(enrollment, groupMetadata.id(), parentGroupInstance, cycleIndex,
                dueDate, openedAt);

        PlanDefinitionParser.MembersCreatedOnOpen membersCreatedOnOpen = PlanDefinitionParser.membersCreatedOnOpen(
                groupMetadata.id(), enrollment.protocol().steps(), enrollment.protocol().groups(),
                enrollment.protocol().dependencyGraph());
        for (PlanDefinitionParser.StepMetadata memberStepMetadata : membersCreatedOnOpen.steps()) {
            // The matched step is left to its event, which creates it due when the event arrived: created
            // here, it would take this instance's due date, often unknown, and never be judged. An entry step
            // is left to its event, or to the work that expects it.
            if (memberStepMetadata.id().equals(matchedStepId) || isAnEntryStep(enrollment, memberStepMetadata)) {
                continue;
            }
            FirstStepDueDate firstStepDueDate = firstStepDueDate(enrollment, memberStepMetadata, groupInstance);
            if (firstStepDueDate.waits()) {
                continue; // a step it follows, outside the group, isn't done: created when it is
            }
            enrollment.add(stepCreationService.createStepRows(enrollment.protocolInstance(), memberStepMetadata,
                    firstStepDueDate.dueDate(), groupInstance));
        }
        for (PlanDefinitionParser.GroupMetadata memberGroupMetadata : membersCreatedOnOpen.groups()) {
            // A group that repeats by visit waits for an event of its own: its cycles start when the patient comes.
            if (onlyAnEventOpens(memberGroupMetadata, null)) {
                continue;
            }
            if (followsAStepNotDoneYet(enrollment, memberGroupMetadata, groupInstance)) {
                continue; // like a first step, it waits: openFollowingGroup opens it when that step is done
            }
            openNextGroupInstance(enrollment, memberGroupMetadata, groupInstance, Optional.empty(), openedAt,
                    matchedStepId);
        }
        return groupInstance;
    }

    /**
     * Insert a group instance row. The caller holds the enrollment's lock and has found none; the unique
     * key is the backstop.
     */
    private StepGroupInstance insert(Enrollment enrollment, String groupActionId,
                                     StepGroupInstance parentGroupInstance, int cycleIndex,
                                     OffsetDateTime dueDate, OffsetDateTime openedAt) {
        StepGroupInstance groupInstance = groupInstanceRepository.save(StepGroupInstance.builder()
                .protocolInstance(enrollment.protocolInstance())
                .parentGroupInstance(parentGroupInstance)
                .groupActionId(groupActionId)
                .cycleIndex(cycleIndex)
                .dueDate(dueDate)
                .openedAt(openedAt)
                .build());

        log.info("Opened step group instance: group={}, cycle={}, instanceId={}, groupInstanceId={}, due={}",
                groupActionId, cycleIndex, enrollment.protocolInstance().getId(), groupInstance.getId(), dueDate);
        return groupInstance;
    }

    // ── Due dates ──

    /**
     * When an instance of the group is due, as the protocol plans it — never the time of the event
     * that happened to open it, which would make a group judged late as soon as its second step arrived.
     *
     * <p>From the group's prerequisites when it has any: the latest of their finish times plus each
     * edge's offset. A prerequisite still in flight means the date can't be known yet, so the answer
     * {@link PlannedDueDate#waits waits}; one with no step instance at all is ignored, as it is for steps. A prerequisite that shares a
     * group with this one is read inside {@code parentGroupInstance}, so a later visit's group counts from
     * that visit's check-in, not an earlier visit's. A group with no prerequisites takes its parent
     * instance's due date, and a top-level one has none.
     */
    private PlannedDueDate plannedDueDate(Enrollment enrollment, PlanDefinitionParser.GroupMetadata groupMetadata,
                                          StepGroupInstance parentGroupInstance) {
        PlanDefinitionParser.DependencyGraph graph = enrollment.protocol().dependencyGraph();
        OffsetDateTime latestDueDate = null;
        boolean anyFinished = false;
        for (String prerequisiteId : graph.predecessorsOf(groupMetadata.id())) {
            PlanDefinitionParser.RelatedStepInfo edge = graph.groupSuccessorsOf(prerequisiteId).stream()
                    .filter(groupDependency -> groupDependency.group().id().equals(groupMetadata.id()))
                    .map(PlanDefinitionParser.GroupDependency::edge)
                    .findFirst()
                    .orElse(null);
            if (edge == null) {
                continue;
            }
            PrerequisiteProgress progress = prerequisiteProgress(enrollment, prerequisiteId, parentGroupInstance);
            if (progress.inFlight()) {
                return PlannedDueDate.WAITS; // can't be known until that prerequisite finishes
            }
            if (progress.finishedAt() == null) {
                continue; // never recorded: ignored, as it is for steps
            }
            // after-start counts from when the prerequisite became due, when it has a due date; anything else
            // from when it finished.
            OffsetDateTime baseTime = "after-start".equals(edge.relationship()) && progress.startedAt() != null
                    ? progress.startedAt()
                    : progress.finishedAt();
            OffsetDateTime dueDate = StepCreationService.calculateDueDate(baseTime, edge);
            if (latestDueDate == null || dueDate.isAfter(latestDueDate)) {
                latestDueDate = dueDate;
            }
            anyFinished = true;
        }
        if (anyFinished) {
            return new PlannedDueDate(false, latestDueDate);
        }
        // No prerequisite has finished: the parent instance's due date, and none for a top-level group.
        return new PlannedDueDate(false, parentGroupInstance != null ? parentGroupInstance.getDueDate() : null);
    }

    /**
     * A group instance's planned due date, or that it {@code waits} for a prerequisite still in flight.
     *
     * @param dueDate the due date; null for none
     */
    private record PlannedDueDate(boolean waits, OffsetDateTime dueDate) {
        static final PlannedDueDate WAITS = new PlannedDueDate(true, null);
    }

    /**
     * Whether a nested group, about to open with its parent, follows a step that isn't done yet. Such a step
     * is outside the parent: a first member follows nothing inside it. The group then waits, as a first step
     * does (see {@link #firstStepDueDate}), and {@link #openFollowingGroup} opens it when that step is done.
     */
    private boolean followsAStepNotDoneYet(Enrollment enrollment, PlanDefinitionParser.GroupMetadata groupMetadata,
                                           StepGroupInstance parentGroupInstance) {
        return enrollment.protocol().dependencyGraph().predecessorsOf(groupMetadata.id()).stream()
                .anyMatch(prerequisiteId ->
                        prerequisiteProgress(enrollment, prerequisiteId, parentGroupInstance).finishedAt() == null);
    }

    /**
     * Whether a group opening its first instance inside {@code parentGroupInstance} follows a step done in an
     * earlier cycle (see {@link #inALaterCycle}).
     */
    private boolean followsAStepFromAnEarlierCycle(Enrollment enrollment,
                                                   PlanDefinitionParser.GroupMetadata groupMetadata,
                                                   StepGroupInstance parentGroupInstance) {
        return parentGroupInstance != null
                && enrollment.protocol().dependencyGraph().predecessorsOf(groupMetadata.id()).stream()
                        .anyMatch(prerequisiteId -> inALaterCycle(enrollment, parentGroupInstance, prerequisiteId));
    }

    /**
     * Whether {@code groupInstance} is in a later cycle than the one the step {@code prerequisiteId} was done
     * for. Walk out from {@code groupInstance} to the first group that also holds that step. If any instance
     * on the way is not cycle 0, the patient came back after the step was done, so the step's completion plus
     * an offset is already past. Beyond that group, a new cycle brings its own step: the next pregnancy has
     * its own registration.
     */
    private static boolean inALaterCycle(Enrollment enrollment, StepGroupInstance groupInstance,
                                         String prerequisiteId) {
        PlanDefinitionParser.StepMetadata prerequisiteStepMetadata = enrollment.step(prerequisiteId);
        Set<String> groupsHoldingThePrerequisite = prerequisiteStepMetadata == null
                || prerequisiteStepMetadata.groupId() == null
                ? Set.of()
                : enrollment.groupsFromInnermostOut(prerequisiteStepMetadata.groupId()).stream()
                        .map(PlanDefinitionParser.GroupMetadata::id)
                        .collect(Collectors.toSet());
        for (StepGroupInstance instance = groupInstance; instance != null;
             instance = instance.getParentGroupInstance()) {
            if (groupsHoldingThePrerequisite.contains(instance.getGroupActionId())) {
                return false;
            }
            if (instance.getCycleIndex() != FIRST_CYCLE) {
                return true;
            }
        }
        return false;
    }

    /**
     * When a step created with {@code groupInstance}, as one of its first members, is due.
     * <ul>
     *   <li>A step that follows nothing takes the instance's due date.</li>
     *   <li>A step that follows a step outside the group keeps its own offset. In cycle 0 it is due when the
     *       last of the steps it follows was done, plus its offset.</li>
     *   <li>In a later cycle it has no due date: the patient came back, and the offset would already be past.
     *       That is a later cycle of its own group, or of a group around it that doesn't also hold the step it
     *       follows (see {@link #inALaterCycle}).</li>
     *   <li>While a step it follows isn't done, it {@link FirstStepDueDate#waits waits}: it isn't created now.
     *       Progressive instantiation creates it when that step is done, with its own offset, as for a step
     *       outside any group.</li>
     * </ul>
     * A first member follows nothing inside the group, so every step it follows is outside it.
     */
    private FirstStepDueDate firstStepDueDate(Enrollment enrollment, PlanDefinitionParser.StepMetadata stepMetadata,
                                              StepGroupInstance groupInstance) {
        PlanDefinitionParser.DependencyGraph graph = enrollment.protocol().dependencyGraph();
        OffsetDateTime latestDueDate = null;
        boolean inALaterCycle = false;
        for (String prerequisiteId : graph.predecessorsOf(stepMetadata.id())) {
            PlanDefinitionParser.RelatedStepInfo edge = graph.successorsOf(prerequisiteId).stream()
                    .filter(stepDependency -> stepDependency.step().id().equals(stepMetadata.id()))
                    .map(PlanDefinitionParser.StepDependency::edge)
                    .findFirst()
                    .orElse(null);
            if (edge == null) {
                continue;
            }
            PrerequisiteProgress progress = prerequisiteProgress(enrollment, prerequisiteId, groupInstance);
            if (progress.finishedAt() == null) {
                return FirstStepDueDate.WAITS;
            }
            OffsetDateTime baseTime = "after-start".equals(edge.relationship()) && progress.startedAt() != null
                    ? progress.startedAt()
                    : progress.finishedAt();
            OffsetDateTime dueDate = StepCreationService.calculateDueDate(baseTime, edge);
            if (latestDueDate == null || dueDate.isAfter(latestDueDate)) {
                latestDueDate = dueDate;
            }
            inALaterCycle |= inALaterCycle(enrollment, groupInstance, prerequisiteId);
        }
        if (latestDueDate == null) {
            return new FirstStepDueDate(false, groupInstance.getDueDate()); // follows nothing
        }
        return new FirstStepDueDate(false, inALaterCycle ? null : latestDueDate);
    }

    /**
     * When a group's first member is due, or that it {@code waits} for a step it follows to be done.
     *
     * @param dueDate the step's due date; null for none
     */
    private record FirstStepDueDate(boolean waits, OffsetDateTime dueDate) {
        static final FirstStepDueDate WAITS = new FirstStepDueDate(true, null);
    }

    /** Where a prerequisite stands: in flight, finished (with when it started and finished), or neither. */
    private record PrerequisiteProgress(boolean inFlight, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        static final PrerequisiteProgress NOT_RECORDED = new PrerequisiteProgress(false, null, null);
        static final PrerequisiteProgress IN_FLIGHT = new PrerequisiteProgress(true, null, null);
    }

    private PrerequisiteProgress prerequisiteProgress(Enrollment enrollment, String prerequisiteId,
                                                      StepGroupInstance fromGroupInstance) {
        List<StepInstance> prerequisiteStepInstances =
                stepInstanceScope.stepInstancesInScope(enrollment, prerequisiteId, fromGroupInstance);
        Optional<StepInstance> lastCompletion = prerequisiteStepInstances.stream()
                .filter(stepInstance -> stepInstance.getStepStatus() == StepStatus.COMPLETED
                        && stepInstance.getCompletedAt() != null)
                .max(Comparator.comparing(StepInstance::getCompletedAt));
        if (lastCompletion.isPresent()) {
            StepInstance completedStepInstance = lastCompletion.get();
            return new PrerequisiteProgress(false, completedStepInstance.getDueDate(),
                    completedStepInstance.getCompletedAt());
        }
        return prerequisiteStepInstances.stream().anyMatch(StepInstanceScope::isOutstanding)
                ? PrerequisiteProgress.IN_FLIGHT
                : PrerequisiteProgress.NOT_RECORDED;
    }
}
