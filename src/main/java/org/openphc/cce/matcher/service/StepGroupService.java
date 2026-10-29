package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.CycleBoundary;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Step group instances: which instance of a group an event or a piece of work goes to, opening one with
 * the steps it holds from the start, and reading a step's rows within the instance they count in.
 *
 * <p>Every method here expects the caller to hold the enrollment's lock
 * ({@link ProtocolInstanceService#lockForStepGroups}). {@code siblings} is the enrollment's step rows,
 * read once by the caller; a method that creates rows appends them, so later work in the same
 * transaction sees them.
 */
@Service
@Transactional
public class StepGroupService {

    private static final Logger log = LoggerFactory.getLogger(StepGroupService.class);

    private final StepGroupInstanceRepository groupInstanceRepository;
    private final StepCreationService stepCreationService;
    private final ZoneId calendarZone;

    /**
     * @param calendarZone the deployment's time zone, in which a {@code calendar-day} group's days are
     *                     counted. One per deployment: clinical times are stored in UTC, and a check-in at
     *                     23:30 UTC is already the next day in Kigali.
     */
    public StepGroupService(StepGroupInstanceRepository groupInstanceRepository,
                            StepCreationService stepCreationService,
                            @Value("${cce.step-groups.calendar-zone:UTC}") ZoneId calendarZone) {
        this.groupInstanceRepository = groupInstanceRepository;
        this.stepCreationService = stepCreationService;
        this.calendarZone = calendarZone;
    }

    // ── Which instance ──

    /**
     * The instance of {@code matchedStep}'s group that an event for it goes to. From the top-level group
     * down: each group's latest cycle within its parent's instance, a first cycle when none is open, or a
     * new cycle when the event starts one (see {@link #startsNewCycle}). A new cycle of an enclosing group
     * creates its first members with it, so the inner groups start afresh.
     *
     * <p>A cycle opened because the patient came back has no planned due date: its predecessor's
     * completion plus offset would already be past.
     *
     * @param occurredAt the event's clinical time: which day it falls on, and the opening time of any
     *                   instance it opens
     */
    public StepGroupInstance groupInstanceForEvent(ProtocolInstance protocolInstance,
                                                   PlanDefinitionParser.StepMetadata matchedStep,
                                                   ParsedProtocolCache.ParsedProtocol protocol,
                                                   List<StepInstance> siblings, OffsetDateTime occurredAt) {
        return groupInstanceForEvent(protocolInstance, matchedStep.groupId(), matchedStep, protocol, siblings,
                occurredAt);
    }

    private StepGroupInstance groupInstanceForEvent(ProtocolInstance protocolInstance, String groupId,
                                                    PlanDefinitionParser.StepMetadata matchedStep,
                                                    ParsedProtocolCache.ParsedProtocol protocol,
                                                    List<StepInstance> siblings, OffsetDateTime occurredAt) {
        PlanDefinitionParser.GroupMetadata group = protocol.group(groupId);
        StepGroupInstance parent = group.parentGroupId() == null
                ? null
                : groupInstanceForEvent(protocolInstance, group.parentGroupId(), matchedStep, protocol, siblings,
                        occurredAt);

        Optional<StepGroupInstance> latestCycle = latestCycle(protocolInstance, groupId, parent);
        if (latestCycle.isEmpty()) {
            return openGroupInstance(protocolInstance, group, parent, 0,
                    plannedDueDate(protocolInstance, group, parent, protocol, siblings), occurredAt, protocol,
                    siblings, matchedStep.id());
        }
        if (startsNewCycle(group, latestCycle.get(), matchedStep, protocol, siblings, occurredAt)) {
            return openGroupInstance(protocolInstance, group, parent, latestCycle.get().getCycleIndex() + 1, null,
                    occurredAt, protocol, siblings, matchedStep.id());
        }
        return latestCycle.get();
    }

    /**
     * Whether an event for {@code matchedStep} at {@code occurredAt} starts a new cycle of {@code group},
     * after {@code latestCycle}. A {@code calendar-day} group starts one for an event on a later day; an
     * earlier or the same day joins the latest cycle. An {@code entry-step} group starts one when the event
     * is for one of its entry steps, and the latest cycle already has that step completed and none awaiting
     * it. Any other event, and any group that does not repeat by visit, keeps the latest cycle.
     */
    private boolean startsNewCycle(PlanDefinitionParser.GroupMetadata group, StepGroupInstance latestCycle,
                                   PlanDefinitionParser.StepMetadata matchedStep,
                                   ParsedProtocolCache.ParsedProtocol protocol,
                                   List<StepInstance> siblings, OffsetDateTime occurredAt) {
        if (group.cycleBoundary() == CycleBoundary.CALENDAR_DAY) {
            return isLaterDay(latestCycle, occurredAt);
        }
        if (group.cycleBoundary() != CycleBoundary.ENTRY_STEP
                || PlanDefinitionParser.cycleEntrySteps(group.id(), protocol.steps(), protocol.groups(),
                        protocol.dependencyGraph()).stream()
                        .noneMatch(entryStep -> entryStep.id().equals(matchedStep.id()))) {
            return false;
        }
        List<StepInstance> matchedStepRows = siblings.stream()
                .filter(s -> matchedStep.id().equals(s.getActionId()) && isDirectlyIn(s, latestCycle))
                .toList();
        return matchedStepRows.stream().anyMatch(s -> s.getStepStatus() == StepStatus.COMPLETED)
                && matchedStepRows.stream().noneMatch(s -> s.getStepStatus() == StepStatus.NOT_STARTED);
    }

    /**
     * Whether {@code at} falls on a later calendar day, in the deployment's time zone, than the day
     * {@code cycle} opened. A cycle's day is not stored: the first event of its day opened it.
     */
    private boolean isLaterDay(StepGroupInstance cycle, OffsetDateTime at) {
        return at.atZoneSameInstant(calendarZone).toLocalDate()
                .isAfter(cycle.getOpenedAt().atZoneSameInstant(calendarZone).toLocalDate());
    }

    /**
     * The instance of {@code groupId} that work not brought by an event of the group's own joins: a step
     * following a step in another group, a backfilled step, a group following a step. A group instance
     * around {@code anchor} comes first, so the work stays in the cycle that gave rise to it; otherwise
     * the group's latest cycle within its parent's, opened as a first cycle when there is none.
     *
     * <p>Empty when that would open a {@code calendar-day} cycle: only an event for one of its steps opens
     * one, so the cycle's day is the day the patient came.
     *
     * @param anchor   the instance of the step whose completion the work comes from, or null
     * @param openedAt the clinical time of whatever needs the instance
     */
    public Optional<StepGroupInstance> groupInstanceForWork(ProtocolInstance protocolInstance, String groupId,
                                                            StepGroupInstance anchor,
                                                            ParsedProtocolCache.ParsedProtocol protocol,
                                                            List<StepInstance> siblings, OffsetDateTime openedAt) {
        StepGroupInstance groupInstanceAroundAnchor = enclosingGroupInstance(anchor, groupId);
        if (groupInstanceAroundAnchor != null) {
            return Optional.of(groupInstanceAroundAnchor);
        }
        PlanDefinitionParser.GroupMetadata group = protocol.group(groupId);
        StepGroupInstance parent = null;
        if (group.parentGroupId() != null) {
            Optional<StepGroupInstance> parentGroupInstance = groupInstanceForWork(protocolInstance,
                    group.parentGroupId(), anchor, protocol, siblings, openedAt);
            if (parentGroupInstance.isEmpty()) {
                return Optional.empty();
            }
            parent = parentGroupInstance.get();
        }
        Optional<StepGroupInstance> latestCycle = latestCycle(protocolInstance, groupId, parent);
        if (latestCycle.isPresent() || group.cycleBoundary() == CycleBoundary.CALENDAR_DAY) {
            return latestCycle;
        }
        return Optional.of(openGroupInstance(protocolInstance, group, parent, 0,
                plannedDueDate(protocolInstance, group, parent, protocol, siblings), openedAt, protocol, siblings,
                null));
    }

    /**
     * A group's latest cycle within one instance of its parent, or within the enrollment for a top-level
     * group. The parent is an instance, not a group: a nested group's cycles are numbered within each
     * instance of its parent, so each visit's Consultation has its own cycle 0.
     */
    private Optional<StepGroupInstance> latestCycle(ProtocolInstance protocolInstance, String groupActionId,
                                                    StepGroupInstance parent) {
        return groupInstanceRepository.findLatestCycle(protocolInstance.getId(), groupActionId,
                parent == null ? null : parent.getId());
    }

    // ── Opening ──

    /**
     * Open the mandatory groups that follow {@code completedStep}, now that it has completed. A group with
     * other prerequisites still in flight waits for them; each of their completions re-runs this. Optional
     * groups are never opened in advance, only when one of their steps is matched, and neither is a
     * {@code calendar-day} group, whose first cycle is the day of its first event.
     */
    public void openGroupsFollowing(StepInstance completedStep,
                                    ParsedProtocolCache.ParsedProtocol protocol,
                                    List<StepInstance> siblings) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();
        for (PlanDefinitionParser.GroupDependency dependency
                : protocol.dependencyGraph().groupSuccessorsOf(completedStep.getActionId())) {
            PlanDefinitionParser.GroupMetadata group = dependency.group();
            if (!group.isMandatory() || group.cycleBoundary() == CycleBoundary.CALENDAR_DAY) {
                continue;
            }
            StepGroupInstance parent = null;
            if (group.parentGroupId() != null) {
                Optional<StepGroupInstance> parentGroupInstance = groupInstanceForWork(protocolInstance,
                        group.parentGroupId(), completedStep.getStepGroupInstance(), protocol, siblings,
                        completedStep.getCompletedAt());
                if (parentGroupInstance.isEmpty()) {
                    continue; // inside a calendar-day group with no cycle yet
                }
                parent = parentGroupInstance.get();
            }
            if (latestCycle(protocolInstance, group.id(), parent).isPresent()) {
                continue;
            }
            OffsetDateTime dueDate = plannedDueDate(protocolInstance, group, parent, protocol, siblings);
            if (dueDate == null) {
                log.debug("Deferring group {} — another prerequisite is still in flight (instanceId={})",
                        group.id(), protocolInstance.getId());
                continue;
            }
            openGroupInstance(protocolInstance, group, parent, 0, dueDate, completedStep.getCompletedAt(), protocol,
                    siblings, null);
        }
    }

    /**
     * Open an instance of a group and create what it holds from the start: its mandatory members with
     * no predecessor inside the group. Steps are due at the instance's due date; nested groups open
     * with their own planned due date, except a {@code calendar-day} group, which only an event opens.
     * Later members arrive through progressive instantiation, with the due dates their own
     * {@code relatedAction} offsets give them. The caller has checked that this cycle doesn't exist.
     *
     * @param matchedStepId a step being created by an event, which opening must not create with the
     *                      others; null when there is none
     */
    private StepGroupInstance openGroupInstance(ProtocolInstance protocolInstance,
                                                PlanDefinitionParser.GroupMetadata group,
                                                StepGroupInstance parent, int cycleIndex, OffsetDateTime dueDate,
                                                OffsetDateTime openedAt,
                                                ParsedProtocolCache.ParsedProtocol protocol,
                                                List<StepInstance> siblings,
                                                String matchedStepId) {
        StepGroupInstance stepGroupInstance = insert(protocolInstance, group.id(), parent, cycleIndex, dueDate,
                openedAt);

        PlanDefinitionParser.MembersCreatedOnOpen membersCreatedOnOpen = PlanDefinitionParser.membersCreatedOnOpen(
                group.id(), protocol.steps(), protocol.groups(), protocol.dependencyGraph());
        for (PlanDefinitionParser.StepMetadata member : membersCreatedOnOpen.steps()) {
            if (!member.id().equals(matchedStepId)) {
                siblings.addAll(stepCreationService.createStepRows(protocolInstance, member,
                        stepGroupInstance.getDueDate(), stepGroupInstance));
            }
        }
        for (PlanDefinitionParser.GroupMetadata member : membersCreatedOnOpen.groups()) {
            if (member.cycleBoundary() != CycleBoundary.CALENDAR_DAY) {
                openGroupInstance(protocolInstance, member, stepGroupInstance, 0,
                        plannedDueDate(protocolInstance, member, stepGroupInstance, protocol, siblings),
                        openedAt, protocol, siblings, matchedStepId);
            }
        }
        return stepGroupInstance;
    }

    /**
     * Insert a group instance row. The caller holds the enrollment's lock and has found none; the unique
     * key is the backstop.
     */
    private StepGroupInstance insert(ProtocolInstance protocolInstance, String groupActionId,
                                     StepGroupInstance parent, int cycleIndex,
                                     OffsetDateTime dueDate, OffsetDateTime openedAt) {
        StepGroupInstance groupInstance = groupInstanceRepository.save(StepGroupInstance.builder()
                .protocolInstance(protocolInstance)
                .parentGroupInstance(parent)
                .groupActionId(groupActionId)
                .cycleIndex(cycleIndex)
                .dueDate(dueDate)
                .openedAt(openedAt)
                .build());

        log.info("Opened step group instance: group={}, cycle={}, instanceId={}, groupInstanceId={}, due={}",
                groupActionId, cycleIndex, protocolInstance.getId(), groupInstance.getId(), dueDate);
        return groupInstance;
    }

    /**
     * When an instance of {@code group} is due, as the protocol plans it — never the time of the event
     * that happened to open it, which would make a group judged late as soon as its second step arrived.
     *
     * <p>From the group's prerequisites when it has any: the latest of their finish times plus each
     * edge's offset. A prerequisite still in flight means the date can't be known yet, so the answer is
     * null; one with no row at all is ignored, as it is for steps. A prerequisite that shares a group with
     * this one is read inside {@code parent}, so a later visit's group counts from that visit's check-in,
     * not an earlier visit's. A group with no prerequisites takes its parent instance's due date, and a
     * top-level one has none.
     */
    private OffsetDateTime plannedDueDate(ProtocolInstance protocolInstance,
                                          PlanDefinitionParser.GroupMetadata group,
                                          StepGroupInstance parent,
                                          ParsedProtocolCache.ParsedProtocol protocol,
                                          List<StepInstance> siblings) {
        PlanDefinitionParser.DependencyGraph graph = protocol.dependencyGraph();
        OffsetDateTime latestDueDate = null;
        boolean anyFinished = false;
        for (String prerequisiteId : graph.predecessorsOf(group.id())) {
            PlanDefinitionParser.RelatedStepInfo edge = graph.groupSuccessorsOf(prerequisiteId).stream()
                    .filter(dependency -> dependency.group().id().equals(group.id()))
                    .map(PlanDefinitionParser.GroupDependency::edge)
                    .findFirst()
                    .orElse(null);
            if (edge == null) {
                continue;
            }
            PrerequisiteProgress progress = prerequisiteProgress(prerequisiteId, parent, protocolInstance, protocol,
                    siblings);
            if (progress.inFlight()) {
                return null;
            }
            if (progress.finishedAt() == null) {
                continue;
            }
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
        return parent != null ? parent.getDueDate() : null;
    }

    /** Where a prerequisite stands: in flight, finished (with when it started and finished), or neither. */
    private record PrerequisiteProgress(boolean inFlight, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        static final PrerequisiteProgress NOT_RECORDED = new PrerequisiteProgress(false, null, null);
        static final PrerequisiteProgress IN_FLIGHT = new PrerequisiteProgress(true, null, null);
    }

    private PrerequisiteProgress prerequisiteProgress(String prerequisiteId, StepGroupInstance fromGroupInstance,
                                                      ProtocolInstance protocolInstance,
                                                      ParsedProtocolCache.ParsedProtocol protocol,
                                                      List<StepInstance> siblings) {
        List<StepInstance> prerequisiteRows =
                rowsInScope(prerequisiteId, fromGroupInstance, protocolInstance, protocol, siblings);
        Optional<StepInstance> lastCompletion = prerequisiteRows.stream()
                .filter(s -> s.getStepStatus() == StepStatus.COMPLETED && s.getCompletedAt() != null)
                .max(Comparator.comparing(StepInstance::getCompletedAt));
        if (lastCompletion.isPresent()) {
            StepInstance completedRow = lastCompletion.get();
            return new PrerequisiteProgress(false, completedRow.getDueDate(), completedRow.getCompletedAt());
        }
        return prerequisiteRows.stream().anyMatch(StepInstanceService::isOutstandingStep)
                ? PrerequisiteProgress.IN_FLIGHT
                : PrerequisiteProgress.NOT_RECORDED;
    }

    // ── Rows within an instance ──

    /**
     * {@code stepActionId}'s rows that count for work in {@code fromGroupInstance}, such as a predecessor's rows
     * when a step completes there:
     * <ul>
     *   <li>a step that shares a group with that work: its rows in the shared instance, so one cycle never
     *       sees another's;</li>
     *   <li>a step in a group that shares none: its rows in its group's latest cycle, inside its parent's
     *       latest, where new work goes. A top-level treatment after a visit's prescription sees this visit's
     *       prescription, not an earlier visit's;</li>
     *   <li>a step outside every group: all its rows in the enrollment, since it happens once per
     *       enrollment.</li>
     * </ul>
     */
    public List<StepInstance> rowsInScope(String stepActionId, StepGroupInstance fromGroupInstance,
                                          ProtocolInstance protocolInstance,
                                          ParsedProtocolCache.ParsedProtocol protocol,
                                          List<StepInstance> siblings) {
        StepGroupInstance sharedGroupInstance = innermostSharedGroupInstance(fromGroupInstance, stepActionId, protocol);
        PlanDefinitionParser.StepMetadata stepMetadata = protocol.step(stepActionId);
        StepGroupInstance scope = sharedGroupInstance != null || stepMetadata == null || stepMetadata.groupId() == null
                ? sharedGroupInstance
                : latestGroupInstancePath(protocolInstance, stepMetadata.groupId(), protocol);
        return siblings.stream()
                .filter(s -> stepActionId.equals(s.getActionId()))
                .filter(s -> scope == null || isInsideGroupInstance(s.getStepGroupInstance(), scope))
                .toList();
    }

    /**
     * The deepest instance on the way down to {@code groupId}'s latest cycle, taking the latest cycle at
     * each level from the top-level group: the group's own latest cycle, or the instance where it would
     * open when it hasn't yet, which holds none of its rows. Null when not even the top-level group is open,
     * in which case none of its steps has a row.
     */
    private StepGroupInstance latestGroupInstancePath(ProtocolInstance protocolInstance, String groupId,
                                                      ParsedProtocolCache.ParsedProtocol protocol) {
        LinkedList<String> groupIdsTopDown = new LinkedList<>();
        for (String enclosingGroupId = groupId; enclosingGroupId != null;
             enclosingGroupId = protocol.group(enclosingGroupId).parentGroupId()) {
            groupIdsTopDown.addFirst(enclosingGroupId);
        }
        StepGroupInstance deepestGroupInstance = null;
        for (String levelGroupId : groupIdsTopDown) {
            Optional<StepGroupInstance> latestCycle = latestCycle(protocolInstance, levelGroupId, deepestGroupInstance);
            if (latestCycle.isEmpty()) {
                break;
            }
            deepestGroupInstance = latestCycle.get();
        }
        return deepestGroupInstance;
    }

    /**
     * {@code fromGroupInstance}'s instance of the innermost group that also encloses {@code stepActionId}, or
     * null.
     */
    private static StepGroupInstance innermostSharedGroupInstance(StepGroupInstance fromGroupInstance,
                                                                  String stepActionId,
                                                                  ParsedProtocolCache.ParsedProtocol protocol) {
        PlanDefinitionParser.StepMetadata stepMetadata = protocol.step(stepActionId);
        if (fromGroupInstance == null || stepMetadata == null || stepMetadata.groupId() == null) {
            return null;
        }
        Set<String> enclosingGroupIds = new HashSet<>();
        for (String groupId = stepMetadata.groupId(); groupId != null; ) {
            enclosingGroupIds.add(groupId);
            PlanDefinitionParser.GroupMetadata group = protocol.group(groupId);
            groupId = group != null ? group.parentGroupId() : null;
        }
        for (StepGroupInstance groupInstance = fromGroupInstance; groupInstance != null;
             groupInstance = groupInstance.getParentGroupInstance()) {
            if (enclosingGroupIds.contains(groupInstance.getGroupActionId())) {
                return groupInstance;
            }
        }
        return null;
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

    private static boolean isInsideGroupInstance(StepGroupInstance stepGroupInstance, StepGroupInstance scope) {
        for (StepGroupInstance groupInstance = stepGroupInstance; groupInstance != null;
             groupInstance = groupInstance.getParentGroupInstance()) {
            if (Objects.equals(groupInstance.getId(), scope.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The row for {@code stepActionId} in {@code stepGroupInstance} among {@code siblings}, if there is
     * one.
     */
    static StepInstance findRowInGroupInstance(List<StepInstance> siblings,
                                               StepGroupInstance stepGroupInstance,
                                               String stepActionId) {
        return siblings.stream()
                .filter(s -> stepActionId.equals(s.getActionId()) && isDirectlyIn(s, stepGroupInstance))
                .findFirst()
                .orElse(null);
    }

    /** Whether {@code row} is attached to {@code stepGroupInstance} itself, not to one nested in it. */
    static boolean isDirectlyIn(StepInstance row, StepGroupInstance stepGroupInstance) {
        return row.getStepGroupInstance() != null
                && Objects.equals(row.getStepGroupInstance().getId(), stepGroupInstance.getId());
    }
}
