package org.openphc.cce.matcher.service;

import org.openphc.cce.common.sla.SlaThresholdReader;
import org.openphc.cce.common.deviation.DeviationRecorder;
import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.history.StateTransitionHistoryWriter;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.support.RequiredBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class StepInstanceService {

    private static final Logger log = LoggerFactory.getLogger(StepInstanceService.class);

    private final StepInstanceRepository stepInstanceRepository;
    private final ParsedProtocolCache parsedProtocolCache;
    private final DeviationRecorder deviationRecorder;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;
    private final StateTransitionHistoryWriter stateTransitionHistoryWriter;
    private final StepSlaScheduleService slaScheduleService;
    private final SlaThresholdReader slaThresholdReader;
    private final StepGroupService stepGroupService;
    private final StepCreationService stepCreationService;
    private final ProtocolInstanceService protocolInstanceService;

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               ParsedProtocolCache parsedProtocolCache,
                               DeviationRecorder deviationRecorder,
                               IntelligenceActionEvaluator intelligenceActionEvaluator,
                               StateTransitionHistoryWriter stateTransitionHistoryWriter,
                               StepSlaScheduleService slaScheduleService,
                               SlaThresholdReader slaThresholdReader,
                               StepGroupService stepGroupService,
                               StepCreationService stepCreationService,
                               ProtocolInstanceService protocolInstanceService) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.parsedProtocolCache = parsedProtocolCache;
        this.deviationRecorder = deviationRecorder;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.stateTransitionHistoryWriter = stateTransitionHistoryWriter;
        this.slaScheduleService = slaScheduleService;
        this.slaThresholdReader = slaThresholdReader;
        this.stepGroupService = stepGroupService;
        this.stepCreationService = stepCreationService;
        this.protocolInstanceService = protocolInstanceService;
    }

    /**
     * SLA statuses that can still move: a null status (no threshold judged yet) and {@link
     * SlaStatus#OVERDUE}. {@link SlaStatus#MET} and {@link SlaStatus#MISSED} are settled outcomes with
     * no threshold left to cross.
     */
    private static boolean isLiveSlaStatus(SlaStatus slaStatus) {
        return slaStatus == null || slaStatus == SlaStatus.OVERDUE;
    }

    /**
     * Complete a step instance: record that its event arrived and settle its SLA,
     * trigger progressive step instantiation for dependent steps, and materialize mandatory
     * steps the journey never recorded (see {@link #backfillMissingMandatorySteps}).
     *
     * <p>Completability depends only on {@link StepStatus} — a step whose SLA is already
     * {@link SlaStatus#MISSED} can still be completed by a late event. The row then records both
     * facts: the event arrived, and the SLA was missed.
     */
    public void completeStep(StepInstance step, UUID matchedEventId, String completedBySource,
                             OffsetDateTime occurredAt) {
        if (step.getStepStatus() != StepStatus.NOT_STARTED) {
            throw new IllegalStateException(
                    "Cannot complete step with stepStatus " + step.getStepStatus() + ": " + step.getId());
        }

        // completedAt is the clinical occurrence time (when the act happened), not the ingestion
        // time — so dependent steps' due/missed dates and the SLA outcome reflect real-world timing
        // rather than how long the event took to reach us. Clamp to now(): a step cannot have
        // completed in the future, and a bad/future source clock must not push downstream schedules
        // out. Fall back to now() when no occurrence time was resolved.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime completedAt = (occurredAt != null && !occurredAt.isAfter(now)) ? occurredAt : now;

        // Read once, to anchor an after-start dependent to when this step became active. Not used to
        // settle this step's own SLA: that is the Step SLA Service's judgement, made on its next
        // sweep, by comparing the completed_at recorded below against each threshold.
        SlaThresholdReader.SlaThresholds thresholds = slaThresholdReader.getThresholds(step.getId());

        // sla_status is deliberately left alone. Recording that the work happened and judging whether
        // it was timely are different questions with different owners; this service only schedules the
        // second one (below), and writing both here is what used to require a rule about which service
        // may overwrite the other.
        step.setStepStatus(StepStatus.COMPLETED);
        step.setCompletedAt(completedAt);
        step.setMatchedEventId(matchedEventId);
        step.setCompletedBySource(completedBySource);

        stepInstanceRepository.save(step);

        // Work recorded by the deadline is a verdict waiting to be written, so schedule it now.
        // The Step SLA Service takes the row on its next cycle; without it, a step completed weeks
        // early would sit unjudged until its due date arrived.
        slaScheduleService.scheduleMetIfOnTime(step, completedAt);

        // Capture the COMPLETED transition in append-only history.
        stateTransitionHistoryWriter.recordStepInstanceTransition(step, now);


        log.info("Completed step: stepId={}, actionId={}, completedAt={} (SLA judged by the Step SLA Service)",
                step.getId(), step.getActionId(), completedAt);

        ParsedProtocolCache.ParsedProtocol protocol = parsedProtocol(step.getProtocolInstance());

        // Everything below may open or complete a step group instance, so for a protocol with groups
        // the enrollment is locked first. A protocol without groups is not locked.
        protocolInstanceService.lockForStepGroups(step.getProtocolInstance(), protocol);

        // This instance's step rows, read once and threaded through all three passes.
        //
        // Mutable by design: each pass that creates a row appends it here, so a later pass sees work
        // created earlier in this same transaction. createDependentSteps relies on that for diamond
        // dependencies (a step waiting on both this completion and one of its other dependents), and
        // backfillMissingMandatorySteps relies on it to avoid re-creating a mandatory step that
        // progressive instantiation just materialized.
        List<StepInstance> siblings = new ArrayList<>(
                stepInstanceRepository.findByProtocolInstanceId(step.getProtocolInstance().getId()));

        detectOrderViolations(step, protocol, siblings);
        createDependentSteps(step, protocol, siblings, thresholds);
        backfillMissingMandatorySteps(step, protocol, siblings);
    }

    /**
     * The row an event for a step outside any group completes, or null when none is awaiting its event.
     * A step in a group is found by {@link #findOrCreateGroupedStep} instead.
     *
     * <p>Scoped by {@link StepStatus} alone, so a step whose SLA is already MISSED is still picked
     * up by a late event rather than a duplicate row being created for it.
     *
     * <p>A repeated step completes the row whose due date is nearest the event's clinical time. Ties go to
     * the earlier due date; rows with no due date come after dated ones, oldest first.
     *
     * @param occurredAt the event's clinical occurrence time
     */
    @Transactional(readOnly = true)
    public StepInstance findActionableStep(UUID protocolInstanceId, String stepActionId, OffsetDateTime occurredAt) {
        return stepInstanceRepository.findByProtocolInstanceIdAndActionIdAndStepStatus(
                        protocolInstanceId, stepActionId, StepStatus.NOT_STARTED).stream()
                .min(nearestDueDate(occurredAt))
                .orElse(null);
    }

    private static Comparator<StepInstance> nearestDueDate(OffsetDateTime occurredAt) {
        return Comparator.<StepInstance, Boolean>comparing(s -> s.getDueDate() == null)
                .thenComparingLong(s -> s.getDueDate() == null
                        ? 0L : Math.abs(Duration.between(occurredAt, s.getDueDate()).toMillis()))
                .thenComparing(StepInstance::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StepInstance::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * Whether a step is still genuinely outstanding: its event has not arrived and its SLA can
     * still move. Excludes SLAs already settled — {@code MISSED}, written off, and {@code MET},
     * recorded on time. {@code OVERDUE} is not settled: a late event can still complete such a step.
     */
    static boolean isOutstandingStep(StepInstance step) {
        return step.getStepStatus() == StepStatus.NOT_STARTED && isLiveSlaStatus(step.getSlaStatus());
    }

    /**
     * Detect order violations: when a step completes, check whether any of its mandatory
     * ({@code requiredBehavior="must"}) immediate predecessors is still outstanding (see
     * {@link #isOutstandingStep}). The immediate predecessors of step X are the prerequisites the
     * normalized dependency graph records for it (see
     * {@link PlanDefinitionParser#buildDependencyGraph}).
     */
    private void detectOrderViolations(StepInstance completedStep,
                                       ParsedProtocolCache.ParsedProtocol protocol,
                                       List<StepInstance> siblings) {
        String completedStepId = completedStep.getActionId();

        Set<String> mustStepIds = PlanDefinitionParser.mustStepIds(protocol.steps());

        // Immediate predecessors of this step, restricted to mandatory ones
        List<String> mustPredecessorIds = protocol.dependencyGraph().predecessorsOf(completedStepId).stream()
                .distinct()
                .filter(mustStepIds::contains)
                .toList();

        if (mustPredecessorIds.isEmpty()) {
            return;
        }

        // Check if any must-predecessor steps are still in actionable (incomplete) states
        List<String> incompletePrerequisites = new ArrayList<>();
        for (String predecessorId : mustPredecessorIds) {
            boolean hasIncomplete = stepGroupService.rowsInScope(predecessorId, completedStep.getStepGroupInstance(),
                    completedStep.getProtocolInstance(), protocol, siblings).stream()
                    .anyMatch(StepInstanceService::isOutstandingStep);
            if (hasIncomplete) {
                incompletePrerequisites.add(predecessorId);
            }
        }

        if (!incompletePrerequisites.isEmpty()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("incompletePrerequisites", incompletePrerequisites);
            // Key kept as-is: it is persisted in deviation.metadata and read downstream.
            metadata.put("completedActionId", completedStepId);

            // Once per step: completeStep refuses a step that is not NOT_STARTED, so this cannot run
            // twice for one step.
            Deviation deviation = deviationRecorder.recordDeviation(completedStep,
                    DeviationType.ORDER_VIOLATION, metadata);

            log.warn("Order violation detected: step {} (actionId={}) completed while "
                            + "prerequisite steps {} are still incomplete",
                    completedStep.getId(), completedStepId, incompletePrerequisites);

            intelligenceActionEvaluator.evaluateOnDeviation(completedStep, deviation);
        }
    }

    /**
     * Progressive step instantiation: when a step completes, create the PENDING steps that declare
     * it as a prerequisite, with due dates calculated from their own {@code relatedAction} offsets,
     * and open the mandatory step groups that follow it.
     *
     * <p>{@code relatedAction} is relative rather than forward — a step states how it sits against
     * another — so the forward direction needed here comes from the normalized graph (see
     * {@link PlanDefinitionParser#buildDependencyGraph}). The offset that positions a dependent step
     * is the one on the edge between it and this prerequisite, whichever end declared it.
     *
     * <p>A dependent in a group joins the instance {@link StepGroupService#groupInstanceForWork} gives it, opening it if
     * need be, and is deduplicated within it, so another cycle of the group can create the step again. A
     * dependent outside any group is deduplicated across the enrollment.
     */
    private void createDependentSteps(StepInstance completedStep,
                                      ParsedProtocolCache.ParsedProtocol protocol,
                                      List<StepInstance> siblings,
                                      SlaThresholdReader.SlaThresholds completedThresholds) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();
        PlanDefinitionParser.DependencyGraph graph = protocol.dependencyGraph();

        // after-start: offset from when the prerequisite became active (its scheduled due date)
        // after-end / after (default): offset from when the prerequisite completed (completedAt)
        OffsetDateTime startedAt = completedThresholds.dueDate() != null
                ? completedThresholds.dueDate()
                : completedStep.getCompletedAt();

        for (PlanDefinitionParser.StepDependency dependent : graph.successorsOf(completedStep.getActionId())) {
            PlanDefinitionParser.StepMetadata targetStep = dependent.step();
            PlanDefinitionParser.RelatedStepInfo edge = dependent.edge();

            // Dedup guard: skip if an instance for this step already exists.
            // A target may already exist because it was created reactively by its own
            // trigger (createInitialStep) before its predecessor completed, or because a
            // redelivered event re-completed the predecessor. Without this guard, progressive
            // instantiation would create a duplicate step that later goes overdue/missed and
            // raises a spurious deviation. A step in a group is guarded below, within the
            // instance it would join.
            if (targetStep.groupId() == null && stepInstanceRepository.existsByProtocolInstanceIdAndActionId(
                    protocolInstance.getId(), targetStep.id())) {
                logAlreadyExists(targetStep.id(), protocolInstance);
                continue;
            }

            String requiredBehavior = targetStep.requiredBehavior();

            // Only pre-create PENDING instances for mandatory (must) steps. Optional steps
            // (could / unspecified) are not instantiated on predecessor completion:
            // we may never receive their events, and a dangling PENDING row would later be
            // driven to MISSED and raise a spurious deviation. When an optional step's
            // event does arrive, MatcherEngine.processMatch creates the instance on the fly
            // (createInitialStep) and completes it in the same transaction.
            if (!RequiredBehavior.isMandatory(requiredBehavior)) {
                log.debug("Skipping progressive instantiation of non-mandatory step {} "
                                + "(requiredBehavior={}, instanceId={})",
                        targetStep.id(), requiredBehavior, protocolInstance.getId());
                continue;
            }

            // Fan-in: a step may declare several prerequisites. Wait while any of the others is
            // still in flight, so the due date anchors to the last prerequisite to finish rather
            // than whichever happened to complete first — each of their completions re-runs this
            // method, so the wait resolves itself.
            String blockingPrerequisite = firstInFlightPrerequisite(
                    graph.predecessorsOf(targetStep.id()), completedStep, protocol, siblings);
            if (blockingPrerequisite != null) {
                log.debug("Deferring progressive instantiation of {} — prerequisite {} is still in flight (instanceId={})",
                        targetStep.id(), blockingPrerequisite, protocolInstance.getId());
                continue;
            }

            OffsetDateTime baseTime = "after-start".equals(edge.relationship())
                    ? startedAt
                    : completedStep.getCompletedAt();
            OffsetDateTime dueDate = StepCreationService.calculateDueDate(baseTime, edge);

            StepGroupInstance stepGroupInstance = null;
            if (targetStep.groupId() != null) {
                Optional<StepGroupInstance> targetGroupInstance = stepGroupService.groupInstanceForWork(protocolInstance,
                        targetStep.groupId(), completedStep.getStepGroupInstance(), protocol, siblings,
                        completedStep.getCompletedAt());
                if (targetGroupInstance.isEmpty()) {
                    log.debug("Skipping progressive instantiation of {} — its calendar-day group has no cycle yet "
                            + "(instanceId={})", targetStep.id(), protocolInstance.getId());
                    continue;
                }
                stepGroupInstance = targetGroupInstance.get();
                // Also true when opening the instance just created it, as one of the group's first steps.
                if (stepInstanceRepository.existsByStepGroupInstanceIdAndActionId(
                        stepGroupInstance.getId(), targetStep.id())) {
                    logAlreadyExists(targetStep.id(), protocolInstance);
                    continue;
                }
            }

            for (StepInstance created
                    : stepCreationService.createStepRows(protocolInstance, targetStep, dueDate, stepGroupInstance)) {
                siblings.add(created);
                log.info("Progressive instantiation: created step {} (repeat {}) due at {} (triggered by {}, relationship={})",
                        targetStep.id(), created.getRepeatIndex(), created.getDueDate(),
                        completedStep.getActionId(), edge.relationship());
            }
        }

        stepGroupService.openGroupsFollowing(completedStep, protocol, siblings);
    }

    private static void logAlreadyExists(String stepActionId, ProtocolInstance protocolInstance) {
        log.debug("Skipping progressive instantiation of {} — an instance for this step already exists (instanceId={})",
                stepActionId, protocolInstance.getId());
    }

    /**
     * The first prerequisite of {@code targetStep}, other than the one that just completed, that
     * still has an actionable (in-flight) step instance — or null when none does.
     *
     * <p>A prerequisite with no instance at all does not block: it may never be recorded, and
     * waiting on it would strand the dependent step forever. Neither does one whose SLA has already
     * settled, which will never complete later than now.
     */
    private String firstInFlightPrerequisite(List<String> prerequisiteIds,
                                             StepInstance completedStep,
                                             ParsedProtocolCache.ParsedProtocol protocol,
                                             List<StepInstance> siblings) {
        for (String prerequisiteId : prerequisiteIds) {
            if (prerequisiteId.equals(completedStep.getActionId())) {
                continue;
            }
            boolean inFlight = stepGroupService.rowsInScope(prerequisiteId, completedStep.getStepGroupInstance(),
                    completedStep.getProtocolInstance(), protocol, siblings).stream()
                    .anyMatch(StepInstanceService::isOutstandingStep);
            if (inFlight) {
                return prerequisiteId;
            }
        }
        return null;
    }


    /**
     * Materialize the mandatory steps the journey should already have recorded. Progressive
     * instantiation only works forward from a completed step, so a step created reactively from its
     * own trigger (see {@code MatcherEngine.createInitialStep}) leaves the mandatory steps that
     * should have preceded it with no row at all — they read as "not started" in the journey view
     * and are invisible to the scheduler, so they never surface as a deviation. On every completion,
     * any mandatory predecessor of the progress observed so far (see
     * {@link PlanDefinitionParser#computeMustPredecessorSteps}) that has no step instance is created
     * in PENDING state.
     *
     * <p>Scoped to predecessors — work that is already late — and never to mandatory steps still
     * ahead in the chain. Materializing those would stamp them with this completion's time and so
     * flatten the due dates their own {@code relatedAction} offsets define (e.g. lab-results' +3d
     * after lab-order); they are left to progressive instantiation, which creates them on their
     * predecessor's completion with the intended schedule.
     *
     * <p>Runs after {@link #detectOrderViolations} deliberately: backfilled rows must not count as
     * incomplete prerequisites for the completion that revealed them, so this does not invent an
     * ORDER_VIOLATION at completion time. A mandatory step that is never recorded is instead caught
     * by the scheduler driving the backfilled row DUE and then MISSED. If its event does arrive
     * later, {@link #findActionableStep} picks the row up and completes it (LATE).
     *
     * <p>Idempotent: a mandatory step that already has any step instance — in any state, whether
     * pre-existing or created earlier in this same transaction by progressive instantiation — is
     * left alone. A backfilled step inside a group joins the instance {@link StepGroupService#groupInstanceForWork} gives it.
     *
     * <p>A step also waits on what its enclosing groups wait on: a BP recorded in ANC visit 2, which follows
     * registration, backfills registration when it has no row, though the BP step names no predecessor.
     */
    private void backfillMissingMandatorySteps(StepInstance completedStep,
                                               ParsedProtocolCache.ParsedProtocol protocol,
                                               List<StepInstance> siblings) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();
        List<PlanDefinitionParser.StepMetadata> steps = protocol.steps();

        Set<String> observedStepIds = siblings.stream()
                .map(StepInstance::getActionId)
                .distinct()
                .filter(stepActionId -> !stepGroupService.rowsInScope(stepActionId,
                        completedStep.getStepGroupInstance(), protocolInstance, protocol, siblings).isEmpty())
                .collect(Collectors.toSet());

        List<String> missingMustSteps = PlanDefinitionParser
                .computeMustPredecessorSteps(observedStepIds, steps, protocol.groups(), protocol.dependencyGraph()).stream()
                .filter(stepId -> !observedStepIds.contains(stepId))
                .sorted()
                .toList();

        if (missingMustSteps.isEmpty()) {
            return;
        }

        // Anchor to the clinical time of the completion that revealed the gap. Every backfilled step
        // is a prerequisite that should already have happened, so they are all equally past due —
        // there is no future schedule left to preserve among them — and this keeps them on clinical
        // time rather than the ingestion clock.
        OffsetDateTime dueDate = completedStep.getCompletedAt() != null
                ? completedStep.getCompletedAt()
                : OffsetDateTime.now(ZoneOffset.UTC);

        Map<String, PlanDefinitionParser.StepMetadata> stepsById = steps.stream()
                .collect(Collectors.toMap(PlanDefinitionParser.StepMetadata::id, s -> s, (a, b) -> a));

        for (String stepId : missingMustSteps) {
            PlanDefinitionParser.StepMetadata stepMetadata = stepsById.get(stepId);

            StepGroupInstance stepGroupInstance = null;
            if (stepMetadata != null && stepMetadata.groupId() != null) {
                Optional<StepGroupInstance> targetGroupInstance = stepGroupService.groupInstanceForWork(
                        protocolInstance, stepMetadata.groupId(), completedStep.getStepGroupInstance(), protocol,
                        siblings, dueDate);
                if (targetGroupInstance.isEmpty()) {
                    continue; // a calendar-day group with no cycle yet: only an event of its own opens one
                }
                stepGroupInstance = targetGroupInstance.get();
                if (StepGroupService.findRowInGroupInstance(siblings, stepGroupInstance, stepId) != null) {
                    continue; // opening its group created it, as one of the group's first steps
                }
            }

            OffsetDateTime missedDate = null;
            if (stepMetadata != null && stepMetadata.toleranceDays() != null) {
                missedDate = dueDate.plusDays(stepMetadata.toleranceDays());
            }

            // One instance (repeatIndex 0) regardless of the step's timing.repeat count: this is a
            // placeholder for work that was never recorded, not a scheduled recurrence.
            siblings.add(stepCreationService.createStep(protocolInstance, stepId, 0, dueDate, missedDate, "must",
                    stepGroupInstance));

            log.info("Backfilled unrecorded mandatory step {} as PENDING due at {} "
                            + "(revealed by completion of {}, instanceId={})",
                    stepId, dueDate, completedStep.getActionId(), protocolInstance.getId());
        }
    }

    // ── Step groups ──

    /**
     * The row an event for a step in a group completes: the step's open row in the instance the event
     * goes to, or a further row there, created due at {@code now}, the event's single clock reading, as a
     * step outside a group is.
     *
     * <p>The instance is chosen first, under the enrollment's lock (see
     * {@link StepGroupService#groupInstanceForEvent}), so an earlier cycle's open row is never completed,
     * whatever the event's clinical time. Opening an
     * instance creates its first mandatory steps, except the matched step itself: created with the
     * rest, it would take the instance's due date, which is often unknown when an event opens it, and so
     * would never be judged.
     *
     * @param now        the event's clock reading, the due date of a step the event creates
     * @param occurredAt the event's clinical time: which day it falls on, and the opening time of any
     *                   instance it opens
     */
    public StepInstance findOrCreateGroupedStep(ProtocolInstance protocolInstance,
                                                PlanDefinitionParser.StepMetadata stepMetadata,
                                                OffsetDateTime now, OffsetDateTime occurredAt) {
        ParsedProtocolCache.ParsedProtocol protocol = parsedProtocol(protocolInstance);
        protocolInstanceService.lockForStepGroups(protocolInstance, protocol);
        List<StepInstance> siblings = new ArrayList<>(
                stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()));

        StepGroupInstance stepGroupInstance =
                stepGroupService.groupInstanceForEvent(protocolInstance, stepMetadata, protocol, siblings, occurredAt);

        Optional<StepInstance> actionableStep = siblings.stream()
                .filter(s -> stepMetadata.id().equals(s.getActionId()) && s.getStepStatus() == StepStatus.NOT_STARTED)
                .filter(s -> StepGroupService.isDirectlyIn(s, stepGroupInstance))
                .min(nearestDueDate(occurredAt));
        if (actionableStep.isPresent()) {
            return actionableStep.get();
        }

        OffsetDateTime missedDate = stepMetadata.toleranceDays() != null
                ? now.plusDays(stepMetadata.toleranceDays())
                : null;
        return stepCreationService.createStep(protocolInstance, stepMetadata.id(), 0, now, missedDate,
                stepMetadata.requiredBehavior(), stepGroupInstance);
    }

    private ParsedProtocolCache.ParsedProtocol parsedProtocol(ProtocolInstance protocolInstance) {
        ProtocolDefinition protocolDef = protocolInstance.getProtocolDefinition();
        return parsedProtocolCache.get(protocolDef.getId(), () -> protocolDef.getDefinition().toString());
    }
}
