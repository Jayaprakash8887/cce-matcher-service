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

/**
 * Step group instances: which instance of a group an event or a piece of work goes to, and opening one
 * with the steps it holds from the start.
 *
 * <p>Every method here expects the caller to hold the enrollment's lock
 * ({@link ProtocolInstanceService#lockForStepGroups}) and to have read the enrollment's step instances
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
     * The instance of {@code matchedStepMetadata}'s group that an event for it goes to. Each group, from the
     * top-level one down to the step's own, gets the same rule within the instance just chosen for its
     * parent: the event joins the group's latest instance, unless it opens a new one (see
     * {@link #opensNewGroupInstance}). A new instance of an enclosing group opens its first members with
     * it, so the groups inside it start afresh.
     *
     * @param occurredAt the event's clinical time: which day it falls on, and the opening time of any
     *                   instance it opens
     */
    public StepGroupInstance groupInstanceForEvent(Enrollment enrollment,
                                                   PlanDefinitionParser.StepMetadata matchedStepMetadata,
                                                   OffsetDateTime occurredAt) {
        Optional<MatchedEvent> matchedEvent = Optional.of(new MatchedEvent(matchedStepMetadata, occurredAt));
        StepGroupInstance groupInstance = null;
        for (PlanDefinitionParser.GroupMetadata groupMetadata
                : enrollment.groupsFromTopDown(matchedStepMetadata.groupId())) {
            StepGroupInstance parentGroupInstance = groupInstance;
            Optional<StepGroupInstance> latestGroupInstance =
                    latestGroupInstance(enrollment, groupMetadata.id(), parentGroupInstance);
            groupInstance = opensNewGroupInstance(enrollment, groupMetadata, latestGroupInstance, matchedEvent)
                    ? openNextGroupInstance(enrollment, groupMetadata, parentGroupInstance, latestGroupInstance,
                            occurredAt, matchedStepMetadata.id())
                    : latestGroupInstance.orElseThrow();
        }
        return groupInstance;
    }

    /**
     * The instance of {@code groupId} for work that isn't an event of the group's own: a step following a
     * step in another group, a backfilled step, a group following a step. Each group, from the top-level
     * one down to {@code groupId}, gets the same rule within the instance just chosen for its parent: the
     * instance around {@code anchor} comes first, so the work stays in the cycle that gave rise to it;
     * otherwise the group's latest instance, or a first one when it has none (see
     * {@link #opensNewGroupInstance}).
     *
     * <p>Empty when a {@code calendar-day} group on the way has no instance yet: only an event for one of
     * its steps opens one, so the instance's day is the day the patient came.
     *
     * @param anchor   the instance of the step whose completion the work comes from, or null
     * @param openedAt the clinical time of whatever needs the instance
     */
    public Optional<StepGroupInstance> groupInstanceForWork(Enrollment enrollment, String groupId,
                                                            StepGroupInstance anchor, OffsetDateTime openedAt) {
        StepGroupInstance groupInstance = null;
        for (PlanDefinitionParser.GroupMetadata groupMetadata : enrollment.groupsFromTopDown(groupId)) {
            StepGroupInstance parentGroupInstance = groupInstance;
            StepGroupInstance groupInstanceAroundAnchor = enclosingGroupInstance(anchor, groupMetadata.id());
            if (groupInstanceAroundAnchor != null) {
                // The work stays in the cycle that gave rise to it, even when a later cycle has opened.
                groupInstance = groupInstanceAroundAnchor;
                continue;
            }
            Optional<StepGroupInstance> latestGroupInstance =
                    latestGroupInstance(enrollment, groupMetadata.id(), parentGroupInstance);
            if (opensNewGroupInstance(enrollment, groupMetadata, latestGroupInstance, Optional.empty())) {
                // None yet: work opens the group's first instance.
                groupInstance = openNextGroupInstance(enrollment, groupMetadata, parentGroupInstance,
                        latestGroupInstance, openedAt, null);
            } else if (latestGroupInstance.isPresent()) {
                groupInstance = latestGroupInstance.get();
            } else {
                return Optional.empty(); // a calendar-day group with no instance yet
            }
        }
        return Optional.of(groupInstance);
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
     *   <li><b>None yet:</b> the group has no instance. Only an event opens a {@code calendar-day} group's
     *       first instance (see {@link #onlyAnEventOpens}).</li>
     *   <li><b>New calendar day:</b> for a {@code calendar-day} group, an event on a later day than the
     *       latest instance's.</li>
     *   <li><b>Entry step repeated:</b> for an {@code entry-step} group, an event for one of its entry steps
     *       that the latest instance has already completed.</li>
     * </ol>
     * Work that isn't an event only ever brings the first: otherwise it joins the latest instance.
     *
     * @param matchedEvent the event for one of the group's steps, or empty when other work needs the
     *                     instance
     */
    private boolean opensNewGroupInstance(Enrollment enrollment, PlanDefinitionParser.GroupMetadata groupMetadata,
                                          Optional<StepGroupInstance> latestGroupInstance,
                                          Optional<MatchedEvent> matchedEvent) {
        if (latestGroupInstance.isEmpty()) { // 1. none yet
            return matchedEvent.isPresent() || !onlyAnEventOpens(groupMetadata);
        }
        if (matchedEvent.isEmpty()) {
            return false; // triggers 2 and 3 come only with an event: work joins the latest instance
        }
        if (groupMetadata.cycleBoundary() == CycleBoundary.CALENDAR_DAY) { // 2. new calendar day
            return isOnLaterDay(latestGroupInstance.get(), matchedEvent.get().occurredAt());
        }
        if (groupMetadata.cycleBoundary() == CycleBoundary.ENTRY_STEP) { // 3. entry step repeated
            return repeatsCompletedEntryStep(enrollment, groupMetadata, latestGroupInstance.get(),
                    matchedEvent.get().matchedStepMetadata());
        }
        return false; // a group that doesn't repeat by visit: every event joins its latest instance
    }

    /**
     * Whether only an event for one of the group's steps can open an instance of it. True of a
     * {@code calendar-day} group: an instance's day is the day the patient came, so neither a predecessor
     * completing nor the parent opening opens one.
     */
    private static boolean onlyAnEventOpens(PlanDefinitionParser.GroupMetadata groupMetadata) {
        return groupMetadata.cycleBoundary() == CycleBoundary.CALENDAR_DAY;
    }

    /**
     * Whether {@code at} falls on a later calendar day, in the deployment's time zone, than the day
     * {@code groupInstance} opened. An instance's day is not stored: the first event of its day opened it.
     */
    private boolean isOnLaterDay(StepGroupInstance groupInstance, OffsetDateTime at) {
        return at.atZoneSameInstant(calendarZone).toLocalDate()
                .isAfter(groupInstance.getOpenedAt().atZoneSameInstant(calendarZone).toLocalDate());
    }

    /**
     * Whether {@code matchedStepMetadata} is one of the group's entry steps that {@code latestGroupInstance}
     * has already completed, with none of its step instances there still awaiting an event. An entry step
     * is a direct member step with no predecessor inside the group.
     */
    private static boolean repeatsCompletedEntryStep(Enrollment enrollment,
                                                     PlanDefinitionParser.GroupMetadata groupMetadata,
                                                     StepGroupInstance latestGroupInstance,
                                                     PlanDefinitionParser.StepMetadata matchedStepMetadata) {
        boolean isEntryStep = PlanDefinitionParser.cycleEntrySteps(groupMetadata.id(), enrollment.protocol().steps(),
                        enrollment.protocol().groups(), enrollment.protocol().dependencyGraph()).stream()
                .anyMatch(entryStepMetadata -> entryStepMetadata.id().equals(matchedStepMetadata.id()));
        if (!isEntryStep) {
            return false;
        }
        List<StepInstance> matchedStepInstances = enrollment.stepInstances().stream()
                .filter(stepInstance -> matchedStepMetadata.id().equals(stepInstance.getActionId())
                        && StepInstanceScope.isDirectlyIn(stepInstance, latestGroupInstance))
                .toList();
        return matchedStepInstances.stream()
                .anyMatch(stepInstance -> stepInstance.getStepStatus() == StepStatus.COMPLETED)
                && matchedStepInstances.stream()
                .noneMatch(stepInstance -> stepInstance.getStepStatus() == StepStatus.NOT_STARTED);
    }

    /** An event for a step in a group: the step it matched, and the event's clinical time. */
    private record MatchedEvent(PlanDefinitionParser.StepMetadata matchedStepMetadata, OffsetDateTime occurredAt) {
    }

    // ── Opening ──

    /**
     * Open the first instance of the group {@code groupMetadata} describes, which follows
     * {@code completedStepInstance}, now that it has completed. Optional groups are never opened in
     * advance, only when one of their steps is matched, and neither is a {@code calendar-day} group, whose
     * first instance is the day of its first event. A group with other prerequisites still in flight waits
     * for them; each of their completions runs this again.
     */
    public void openFollowingGroup(Enrollment enrollment, StepInstance completedStepInstance,
                                   PlanDefinitionParser.GroupMetadata groupMetadata) {
        if (!groupMetadata.isMandatory() || onlyAnEventOpens(groupMetadata)) {
            return; // optional and calendar-day groups open only for an event of their own
        }
        StepGroupInstance parentGroupInstance = null;
        if (groupMetadata.parentGroupId() != null) {
            // A nested group opens inside its parent's instance: the one around the completed step when there
            // is one, otherwise the parent's latest.
            Optional<StepGroupInstance> parentForWork = groupInstanceForWork(enrollment,
                    groupMetadata.parentGroupId(), completedStepInstance.getStepGroupInstance(),
                    completedStepInstance.getCompletedAt());
            if (parentForWork.isEmpty()) {
                return; // inside a calendar-day group with no instance yet
            }
            parentGroupInstance = parentForWork.get();
        }
        if (latestGroupInstance(enrollment, groupMetadata.id(), parentGroupInstance).isPresent()) {
            return; // already open
        }
        OffsetDateTime dueDate = plannedDueDate(enrollment, groupMetadata, parentGroupInstance);
        if (dueDate == null) {
            // Its due date counts from its last prerequisite to finish, and another is still in flight;
            // that one's completion runs this again.
            log.debug("Deferring group {} — another prerequisite is still in flight (instanceId={})",
                    groupMetadata.id(), enrollment.protocolInstance().getId());
            return;
        }
        openGroupInstance(enrollment, groupMetadata, parentGroupInstance, FIRST_CYCLE, dueDate,
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
            // The first instance: due when the protocol plans it.
            return openGroupInstance(enrollment, groupMetadata, parentGroupInstance, FIRST_CYCLE,
                    plannedDueDate(enrollment, groupMetadata, parentGroupInstance), openedAt, matchedStepId);
        }
        // A later cycle: the patient came back, past any date the protocol could plan, so it has none.
        return openGroupInstance(enrollment, groupMetadata, parentGroupInstance,
                latestGroupInstance.get().getCycleIndex() + 1, null, openedAt, matchedStepId);
    }

    /**
     * Open an instance of a group and create what it holds from the start: its mandatory members with
     * no predecessor inside the group. Steps are due at the instance's due date; nested groups open their
     * first instance, except a {@code calendar-day} group, which only an event opens. Later members
     * arrive through progressive instantiation, with the due dates their own {@code relatedAction}
     * offsets give them. The caller has checked that this cycle doesn't exist.
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
            // here, it would take this instance's due date, often unknown, and never be judged.
            if (!memberStepMetadata.id().equals(matchedStepId)) {
                enrollment.add(stepCreationService.createStepRows(enrollment.protocolInstance(), memberStepMetadata,
                        groupInstance.getDueDate(), groupInstance));
            }
        }
        for (PlanDefinitionParser.GroupMetadata memberGroupMetadata : membersCreatedOnOpen.groups()) {
            // A calendar-day group waits for an event of its own: its instance's day is the day the patient came.
            if (!onlyAnEventOpens(memberGroupMetadata)) {
                openNextGroupInstance(enrollment, memberGroupMetadata, groupInstance, Optional.empty(), openedAt,
                        matchedStepId);
            }
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
     * edge's offset. A prerequisite still in flight means the date can't be known yet, so the answer is
     * null; one with no step instance at all is ignored, as it is for steps. A prerequisite that shares a
     * group with this one is read inside {@code parentGroupInstance}, so a later visit's group counts from
     * that visit's check-in, not an earlier visit's. A group with no prerequisites takes its parent
     * instance's due date, and a top-level one has none.
     */
    private OffsetDateTime plannedDueDate(Enrollment enrollment, PlanDefinitionParser.GroupMetadata groupMetadata,
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
                return null; // can't be known until that prerequisite finishes
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
            return latestDueDate;
        }
        // No prerequisite has finished: the parent instance's due date, and none for a top-level group.
        return parentGroupInstance != null ? parentGroupInstance.getDueDate() : null;
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
