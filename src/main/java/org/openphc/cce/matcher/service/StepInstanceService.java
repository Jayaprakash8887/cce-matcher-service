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
import org.openphc.cce.common.enums.CycleBoundary;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               ParsedProtocolCache parsedProtocolCache,
                               DeviationRecorder deviationRecorder,
                               IntelligenceActionEvaluator intelligenceActionEvaluator,
                               StateTransitionHistoryWriter stateTransitionHistoryWriter,
                               StepSlaScheduleService slaScheduleService,
                               SlaThresholdReader slaThresholdReader,
                               StepGroupService stepGroupService) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.parsedProtocolCache = parsedProtocolCache;
        this.deviationRecorder = deviationRecorder;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.stateTransitionHistoryWriter = stateTransitionHistoryWriter;
        this.slaScheduleService = slaScheduleService;
        this.slaThresholdReader = slaThresholdReader;
        this.stepGroupService = stepGroupService;
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
     * Create a new step instance: its event is {@link StepStatus#NOT_STARTED} and its {@code sla_status}
     * is null — no threshold has fallen due, so there is nothing to judge yet. The Step SLA Service
     * writes that column, never this service.
     *
     * <p>The due date is recorded on the step: it is the deadline the work was expected by, and what
     * the Step SLA Service measures {@code completed_at} against. The missed date is not, being readable
     * from the {@code MISSED_DATE_REACHED} row that already carries it as both schedule and threshold.
     * Either way the {@code step_sla_state_transition} rows are written in this same transaction, so the
     * step and its SLA schedule commit or roll back together.
     */
    public StepInstance createStep(ProtocolInstance protocolInstance, String stepActionId,
                                   int repeatIndex, OffsetDateTime dueDate,
                                   OffsetDateTime missedDate, String requiredBehavior) {
        return createStep(protocolInstance, stepActionId, repeatIndex, dueDate, missedDate, requiredBehavior, null);
    }

    /**
     * {@link #createStep(ProtocolInstance, String, int, OffsetDateTime, OffsetDateTime, String)} for a
     * step inside a step group, attached to the group occurrence it belongs to.
     *
     * @param stepGroupInstance the occurrence of the step's nearest group, or null outside any group
     */
    public StepInstance createStep(ProtocolInstance protocolInstance, String stepActionId,
                                   int repeatIndex, OffsetDateTime dueDate,
                                   OffsetDateTime missedDate, String requiredBehavior,
                                   StepGroupInstance stepGroupInstance) {
        StepInstance step = StepInstance.builder()
                .protocolInstance(protocolInstance)
                .actionId(stepActionId)
                .stepGroupInstance(stepGroupInstance)
                .repeatIndex(repeatIndex)
                .stepStatus(StepStatus.NOT_STARTED)
                // sla_status stays null: no threshold has fallen due, so there is nothing to judge
                // yet. The Step SLA Service is the only writer of that column.
                //
                // due_date is written here and never again. It is what that service settles MET
                // against, so it has to be fixed before anything can be judged, and moving it later
                // would redate every verdict already reached.
                .dueDate(dueDate)
                .requiredBehavior(requiredBehavior)
                .build();

        step = stepInstanceRepository.save(step);

        slaScheduleService.schedule(step, dueDate, missedDate);

        // Capture the initial NOT_STARTED status in append-only history.
        stateTransitionHistoryWriter.recordStepInstanceTransition(step, step.getCreatedAt());

        log.info("Created step instance: actionId={}, repeatIndex={}, instanceId={}, stepId={}, due={}, missed={}",
                stepActionId, repeatIndex, protocolInstance.getId(), step.getId(), dueDate, missedDate);

        return step;
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

        // Everything below may open or complete a step group occurrence, so for a protocol with groups
        // the enrollment is locked first. A protocol without groups is not locked.
        stepGroupService.lock(step.getProtocolInstance(), protocol);

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
    private boolean isOutstandingStep(StepInstance step) {
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
            boolean hasIncomplete = rowsInScope(predecessorId, completedStep.getStepGroupInstance(),
                    completedStep.getProtocolInstance(), protocol, siblings).stream()
                    .anyMatch(this::isOutstandingStep);
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
     * <p>A dependent in a group joins the occurrence {@link #occurrenceForWork} gives it, opening it if
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
            // occurrence it would join.
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
            OffsetDateTime dueDate = calculateDueDate(baseTime, edge);

            StepGroupInstance stepGroupInstance = null;
            if (targetStep.groupId() != null) {
                Optional<StepGroupInstance> targetOccurrence = occurrenceForWork(protocolInstance,
                        targetStep.groupId(), completedStep.getStepGroupInstance(), protocol, siblings,
                        completedStep.getCompletedAt());
                if (targetOccurrence.isEmpty()) {
                    log.debug("Skipping progressive instantiation of {} — its calendar-day group has no cycle yet "
                            + "(instanceId={})", targetStep.id(), protocolInstance.getId());
                    continue;
                }
                stepGroupInstance = targetOccurrence.get();
                // Also true when opening the occurrence just created it, as one of the group's first steps.
                if (stepInstanceRepository.existsByStepGroupInstanceIdAndActionId(
                        stepGroupInstance.getId(), targetStep.id())) {
                    logAlreadyExists(targetStep.id(), protocolInstance);
                    continue;
                }
            }

            for (StepInstance created
                    : createStepRows(protocolInstance, targetStep, dueDate, stepGroupInstance)) {
                siblings.add(created);
                log.info("Progressive instantiation: created step {} (repeat {}) due at {} (triggered by {}, relationship={})",
                        targetStep.id(), created.getRepeatIndex(), created.getDueDate(),
                        completedStep.getActionId(), edge.relationship());
            }
        }

        openGroupsFollowing(completedStep, protocol, siblings);
    }

    private static void logAlreadyExists(String stepActionId, ProtocolInstance protocolInstance) {
        log.debug("Skipping progressive instantiation of {} — an instance for this step already exists (instanceId={})",
                stepActionId, protocolInstance.getId());
    }

    /**
     * Create a step's rows, attached to {@code stepGroupInstance}: one row, or {@code count} rows a
     * {@code period} apart when the step repeats on its own {@code timing}. tolerance-days is the single
     * grace window: missedDate = dueDate + tolerance.
     *
     * <p>A step created with no due date gets one row and no deadline. That happens only inside a group
     * occurrence whose planned due date is not known yet.
     */
    private List<StepInstance> createStepRows(ProtocolInstance protocolInstance,
                                          PlanDefinitionParser.StepMetadata targetStep,
                                          OffsetDateTime dueDate, StepGroupInstance stepGroupInstance) {
        int repeatCount = 1;
        java.math.BigDecimal repeatPeriod = null;
        String repeatPeriodUnit = null;
        PlanDefinitionParser.TimingInfo timing = targetStep.timing();
        if (dueDate != null && timing != null && timing.count() != null && timing.count() > 1) {
            repeatCount = timing.count();
            repeatPeriod = timing.period();
            repeatPeriodUnit = timing.periodUnit();
        }

        List<StepInstance> createdRows = new ArrayList<>(repeatCount);
        for (int i = 0; i < repeatCount; i++) {
            OffsetDateTime instanceDueDate = dueDate;
            if (i > 0 && repeatPeriod != null && repeatPeriodUnit != null) {
                instanceDueDate = addOffset(dueDate, repeatPeriod.longValue() * i, repeatPeriodUnit);
            }

            OffsetDateTime instanceMissedDate = null;
            if (instanceDueDate != null && targetStep.toleranceDays() != null) {
                instanceMissedDate = instanceDueDate.plusDays(targetStep.toleranceDays());
            }

            createdRows.add(createStep(protocolInstance, targetStep.id(), i, instanceDueDate,
                    instanceMissedDate, targetStep.requiredBehavior(), stepGroupInstance));
        }
        return createdRows;
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
            boolean inFlight = rowsInScope(prerequisiteId, completedStep.getStepGroupInstance(),
                    completedStep.getProtocolInstance(), protocol, siblings).stream()
                    .anyMatch(this::isOutstandingStep);
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
     * left alone. A backfilled step inside a group joins the occurrence {@link #occurrenceForWork} gives it.
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
                .filter(stepActionId -> !rowsInScope(stepActionId, completedStep.getStepGroupInstance(),
                        protocolInstance, protocol, siblings).isEmpty())
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
                Optional<StepGroupInstance> targetOccurrence = occurrenceForWork(protocolInstance,
                        stepMetadata.groupId(), completedStep.getStepGroupInstance(), protocol, siblings, dueDate);
                if (targetOccurrence.isEmpty()) {
                    continue; // a calendar-day group with no cycle yet: only an event of its own opens one
                }
                stepGroupInstance = targetOccurrence.get();
                if (findRowInOccurrence(siblings, stepGroupInstance, stepId) != null) {
                    continue; // opening its group created it, as one of the group's first steps
                }
            }

            OffsetDateTime missedDate = null;
            if (stepMetadata != null && stepMetadata.toleranceDays() != null) {
                missedDate = dueDate.plusDays(stepMetadata.toleranceDays());
            }

            // One instance (repeatIndex 0) regardless of the step's timing.repeat count: this is a
            // placeholder for work that was never recorded, not a scheduled recurrence.
            siblings.add(createStep(protocolInstance, stepId, 0, dueDate, missedDate, "must",
                    stepGroupInstance));

            log.info("Backfilled unrecorded mandatory step {} as PENDING due at {} "
                            + "(revealed by completion of {}, instanceId={})",
                    stepId, dueDate, completedStep.getActionId(), protocolInstance.getId());
        }
    }

    // ── Step groups ──

    /**
     * The row an event for a step in a group completes: the step's open row in the occurrence the event
     * goes to, or a further row there, created due at {@code now}, the event's single clock reading, as a
     * step outside a group is.
     *
     * <p>The occurrence is chosen first, under the enrollment's lock (see {@link #occurrenceForEvent}), so
     * an earlier cycle's open row is never completed, whatever the event's clinical time. Opening an
     * occurrence creates its first mandatory steps, except the matched step itself: created with the
     * rest, it would take the occurrence's due date, which is often unknown when an event opens it, and so
     * would never be judged.
     *
     * @param now        the event's clock reading, the due date of a step the event creates
     * @param occurredAt the event's clinical time: which day it falls on, and the opening time of any
     *                   occurrence it opens
     */
    public StepInstance findOrCreateGroupedStep(ProtocolInstance protocolInstance,
                                                PlanDefinitionParser.StepMetadata stepMetadata,
                                                OffsetDateTime now, OffsetDateTime occurredAt) {
        ParsedProtocolCache.ParsedProtocol protocol = parsedProtocol(protocolInstance);
        stepGroupService.lock(protocolInstance, protocol);
        List<StepInstance> siblings = new ArrayList<>(
                stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()));

        StepGroupInstance stepGroupInstance = occurrenceForEvent(protocolInstance, stepMetadata.groupId(),
                stepMetadata, protocol, siblings, occurredAt);

        Optional<StepInstance> actionableStep = siblings.stream()
                .filter(s -> stepMetadata.id().equals(s.getActionId()) && s.getStepStatus() == StepStatus.NOT_STARTED)
                .filter(s -> isDirectlyIn(s, stepGroupInstance))
                .min(nearestDueDate(occurredAt));
        if (actionableStep.isPresent()) {
            return actionableStep.get();
        }

        OffsetDateTime missedDate = stepMetadata.toleranceDays() != null
                ? now.plusDays(stepMetadata.toleranceDays())
                : null;
        return createStep(protocolInstance, stepMetadata.id(), 0, now, missedDate,
                stepMetadata.requiredBehavior(), stepGroupInstance);
    }

    /**
     * The occurrence of {@code groupId} an event for {@code matched} goes to. From the top-level group
     * down: each group's latest cycle within its parent's occurrence, a first cycle when none is open, or
     * a new cycle when the event starts one (see {@link #startsNewCycle}). A new cycle of an enclosing
     * group opens its entry members with it, so the inner groups start afresh.
     *
     * <p>A cycle opened because the patient came back has no planned due date: its predecessor's
     * completion plus offset would already be past.
     */
    private StepGroupInstance occurrenceForEvent(ProtocolInstance protocolInstance, String groupId,
                                                 PlanDefinitionParser.StepMetadata matchedStep,
                                                 ParsedProtocolCache.ParsedProtocol protocol,
                                                 List<StepInstance> siblings, OffsetDateTime occurredAt) {
        PlanDefinitionParser.GroupMetadata group = protocol.group(groupId);
        StepGroupInstance parent = group.parentGroupId() == null
                ? null
                : occurrenceForEvent(protocolInstance, group.parentGroupId(), matchedStep, protocol, siblings,
                        occurredAt);

        Optional<StepGroupInstance> latestCycle = stepGroupService.latestCycle(protocolInstance, groupId, parent);
        if (latestCycle.isEmpty()) {
            return openOccurrence(protocolInstance, group, parent, 0,
                    plannedDueDate(protocolInstance, group, parent, protocol, siblings), occurredAt, protocol,
                    siblings, matchedStep.id());
        }
        if (startsNewCycle(group, latestCycle.get(), matchedStep, protocol, siblings, occurredAt)) {
            return openOccurrence(protocolInstance, group, parent, latestCycle.get().getCycleIndex() + 1, null,
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
            return stepGroupService.isLaterDay(latestCycle, occurredAt);
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
     * The occurrence of {@code groupId} that work not brought by an event of the group's own joins: a step
     * following a step in another group, a backfilled step, a group following a step. An occurrence
     * around {@code anchor} comes first, so the work stays in the cycle that gave rise to it; otherwise
     * the group's latest cycle within its parent's, opened as a first cycle when there is none.
     *
     * <p>Empty when that would open a {@code calendar-day} cycle: only an event for one of its steps opens
     * one, so the cycle's day is the day the patient came.
     *
     * @param anchor   the occurrence of the step whose completion the work comes from, or null
     * @param openedAt the clinical time of whatever needs the occurrence
     */
    private Optional<StepGroupInstance> occurrenceForWork(ProtocolInstance protocolInstance, String groupId,
                                                          StepGroupInstance anchor,
                                                          ParsedProtocolCache.ParsedProtocol protocol,
                                                          List<StepInstance> siblings, OffsetDateTime openedAt) {
        StepGroupInstance occurrenceAroundAnchor = enclosingOccurrence(anchor, groupId);
        if (occurrenceAroundAnchor != null) {
            return Optional.of(occurrenceAroundAnchor);
        }
        PlanDefinitionParser.GroupMetadata group = protocol.group(groupId);
        StepGroupInstance parent = null;
        if (group.parentGroupId() != null) {
            Optional<StepGroupInstance> parentOccurrence = occurrenceForWork(protocolInstance, group.parentGroupId(),
                    anchor, protocol, siblings, openedAt);
            if (parentOccurrence.isEmpty()) {
                return Optional.empty();
            }
            parent = parentOccurrence.get();
        }
        Optional<StepGroupInstance> latestCycle = stepGroupService.latestCycle(protocolInstance, groupId, parent);
        if (latestCycle.isPresent() || group.cycleBoundary() == CycleBoundary.CALENDAR_DAY) {
            return latestCycle;
        }
        return Optional.of(openOccurrence(protocolInstance, group, parent, 0,
                plannedDueDate(protocolInstance, group, parent, protocol, siblings), openedAt, protocol, siblings,
                null));
    }

    /**
     * Open an occurrence of a group and create what it holds from the start: its mandatory members with
     * no predecessor inside the group. Steps are due at the occurrence's due date; nested groups open
     * with their own planned due date, except a {@code calendar-day} group, which only an event opens.
     * Later members arrive through progressive instantiation, with the due dates their own
     * {@code relatedAction} offsets give them. The caller has checked that this cycle doesn't exist.
     *
     * @param matchedStepId a step being created by an event, which opening must not create as one of the
     *                      first steps; null when there is none
     */
    private StepGroupInstance openOccurrence(ProtocolInstance protocolInstance,
                                             PlanDefinitionParser.GroupMetadata group,
                                             StepGroupInstance parent, int cycleIndex, OffsetDateTime dueDate,
                                             OffsetDateTime openedAt,
                                             ParsedProtocolCache.ParsedProtocol protocol,
                                             List<StepInstance> siblings,
                                             String matchedStepId) {
        StepGroupInstance stepGroupInstance =
                stepGroupService.open(protocolInstance, group.id(), parent, cycleIndex, dueDate, openedAt);

        PlanDefinitionParser.EntryMembers entryMembers = PlanDefinitionParser.entryMembers(
                group.id(), protocol.steps(), protocol.groups(), protocol.dependencyGraph());
        for (PlanDefinitionParser.StepMetadata member : entryMembers.steps()) {
            if (!member.id().equals(matchedStepId)) {
                siblings.addAll(createStepRows(protocolInstance, member, stepGroupInstance.getDueDate(),
                        stepGroupInstance));
            }
        }
        for (PlanDefinitionParser.GroupMetadata member : entryMembers.groups()) {
            if (member.cycleBoundary() != CycleBoundary.CALENDAR_DAY) {
                openOccurrence(protocolInstance, member, stepGroupInstance, 0,
                        plannedDueDate(protocolInstance, member, stepGroupInstance, protocol, siblings),
                        openedAt, protocol, siblings, matchedStepId);
            }
        }
        return stepGroupInstance;
    }

    /**
     * Open the mandatory groups that follow {@code completedStep}, now that it has completed. A group with
     * other prerequisites still in flight waits for them; each of their completions re-runs this. Optional
     * groups are never opened in advance, only when one of their steps is matched, and neither is a
     * {@code calendar-day} group, whose first cycle is the day of its first event.
     */
    private void openGroupsFollowing(StepInstance completedStep,
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
                Optional<StepGroupInstance> parentOccurrence = occurrenceForWork(protocolInstance,
                        group.parentGroupId(), completedStep.getStepGroupInstance(), protocol, siblings,
                        completedStep.getCompletedAt());
                if (parentOccurrence.isEmpty()) {
                    continue; // inside a calendar-day group with no cycle yet
                }
                parent = parentOccurrence.get();
            }
            if (stepGroupService.latestCycle(protocolInstance, group.id(), parent).isPresent()) {
                continue;
            }
            OffsetDateTime dueDate = plannedDueDate(protocolInstance, group, parent, protocol, siblings);
            if (dueDate == null) {
                log.debug("Deferring group {} — another prerequisite is still in flight (instanceId={})",
                        group.id(), protocolInstance.getId());
                continue;
            }
            openOccurrence(protocolInstance, group, parent, 0, dueDate, completedStep.getCompletedAt(), protocol,
                    siblings, null);
        }
    }

    /**
     * When an occurrence of {@code group} is due, as the protocol plans it — never the time of the event
     * that happened to open it, which would make a group judged late as soon as its second step arrived.
     *
     * <p>From the group's prerequisites when it has any: the latest of their finish times plus each
     * edge's offset. A prerequisite still in flight means the date can't be known yet, so the answer is
     * null; one with no row at all is ignored, as it is for steps. A prerequisite that shares a group with
     * this one is read inside {@code parent}, so a later visit's group counts from that visit's check-in,
     * not an earlier visit's. A group with no prerequisites takes its parent occurrence's due date, and a
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
            OffsetDateTime dueDate = calculateDueDate(baseTime, edge);
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
        return prerequisiteRows.stream().anyMatch(this::isOutstandingStep)
                ? PrerequisiteProgress.IN_FLIGHT
                : PrerequisiteProgress.NOT_RECORDED;
    }

    /**
     * {@code stepActionId}'s rows that count for work in {@code fromGroupInstance}, such as a predecessor's rows
     * when a step completes there:
     * <ul>
     *   <li>a step that shares a group with that work: its rows in the shared occurrence, so one cycle never
     *       sees another's;</li>
     *   <li>a step in a group that shares none: its rows in its group's latest cycle, inside its parent's
     *       latest, where new work goes. A top-level treatment after a visit's prescription sees this visit's
     *       prescription, not an earlier visit's;</li>
     *   <li>a step outside every group: all its rows in the enrollment, since it happens once per
     *       enrollment.</li>
     * </ul>
     */
    private List<StepInstance> rowsInScope(String stepActionId, StepGroupInstance fromGroupInstance,
                                           ProtocolInstance protocolInstance,
                                           ParsedProtocolCache.ParsedProtocol protocol,
                                           List<StepInstance> siblings) {
        StepGroupInstance sharedOccurrence = innermostSharedOccurrence(fromGroupInstance, stepActionId, protocol);
        PlanDefinitionParser.StepMetadata stepMetadata = protocol.step(stepActionId);
        StepGroupInstance scope = sharedOccurrence != null || stepMetadata == null || stepMetadata.groupId() == null
                ? sharedOccurrence
                : latestOccurrencePath(protocolInstance, stepMetadata.groupId(), protocol);
        return siblings.stream()
                .filter(s -> stepActionId.equals(s.getActionId()))
                .filter(s -> scope == null || isInsideOccurrence(s.getStepGroupInstance(), scope))
                .toList();
    }

    /**
     * The deepest occurrence on the way down to {@code groupId}'s latest cycle, taking the latest cycle at
     * each level from the top-level group: the group's own latest cycle, or the occurrence where it would
     * open when it hasn't yet, which holds none of its rows. Null when not even the top-level group is open,
     * in which case none of its steps has a row.
     */
    private StepGroupInstance latestOccurrencePath(ProtocolInstance protocolInstance, String groupId,
                                                   ParsedProtocolCache.ParsedProtocol protocol) {
        LinkedList<String> groupIdsTopDown = new LinkedList<>();
        for (String enclosingGroupId = groupId; enclosingGroupId != null;
             enclosingGroupId = protocol.group(enclosingGroupId).parentGroupId()) {
            groupIdsTopDown.addFirst(enclosingGroupId);
        }
        StepGroupInstance deepestOccurrence = null;
        for (String levelGroupId : groupIdsTopDown) {
            Optional<StepGroupInstance> latestCycle =
                    stepGroupService.latestCycle(protocolInstance, levelGroupId, deepestOccurrence);
            if (latestCycle.isEmpty()) {
                break;
            }
            deepestOccurrence = latestCycle.get();
        }
        return deepestOccurrence;
    }

    /**
     * {@code fromGroupInstance}'s occurrence of the innermost group that also encloses {@code stepActionId}, or
     * null.
     */
    private static StepGroupInstance innermostSharedOccurrence(StepGroupInstance fromGroupInstance,
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
        for (StepGroupInstance occurrence = fromGroupInstance; occurrence != null;
             occurrence = occurrence.getParentGroupInstance()) {
            if (enclosingGroupIds.contains(occurrence.getGroupActionId())) {
                return occurrence;
            }
        }
        return null;
    }

    /** {@code fromGroupInstance} or the occurrence around it that belongs to {@code groupId}, or null. */
    private static StepGroupInstance enclosingOccurrence(StepGroupInstance fromGroupInstance, String groupId) {
        for (StepGroupInstance occurrence = fromGroupInstance; occurrence != null;
             occurrence = occurrence.getParentGroupInstance()) {
            if (groupId.equals(occurrence.getGroupActionId())) {
                return occurrence;
            }
        }
        return null;
    }

    private static boolean isInsideOccurrence(StepGroupInstance stepGroupInstance, StepGroupInstance scope) {
        for (StepGroupInstance occurrence = stepGroupInstance; occurrence != null;
             occurrence = occurrence.getParentGroupInstance()) {
            if (Objects.equals(occurrence.getId(), scope.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The row for {@code stepActionId} in {@code stepGroupInstance} among {@code siblings}, if there is
     * one.
     */
    private static StepInstance findRowInOccurrence(List<StepInstance> siblings,
                                                    StepGroupInstance stepGroupInstance,
                                                    String stepActionId) {
        return siblings.stream()
                .filter(s -> stepActionId.equals(s.getActionId()) && isDirectlyIn(s, stepGroupInstance))
                .findFirst()
                .orElse(null);
    }

    /** Whether {@code row} is attached to {@code stepGroupInstance} itself, not to one nested in it. */
    private static boolean isDirectlyIn(StepInstance row, StepGroupInstance stepGroupInstance) {
        return row.getStepGroupInstance() != null
                && Objects.equals(row.getStepGroupInstance().getId(), stepGroupInstance.getId());
    }

    private ParsedProtocolCache.ParsedProtocol parsedProtocol(ProtocolInstance protocolInstance) {
        ProtocolDefinition protocolDef = protocolInstance.getProtocolDefinition();
        return parsedProtocolCache.get(protocolDef.getId(), () -> protocolDef.getDefinition().toString());
    }

    private OffsetDateTime calculateDueDate(OffsetDateTime baseTime,
                                            PlanDefinitionParser.RelatedStepInfo relatedStep) {
        if (relatedStep.offsetValue() == null) {
            return baseTime;
        }

        long offsetAmount = relatedStep.offsetValue().longValue();
        String unit = relatedStep.offsetUnit();

        if (unit == null) {
            return baseTime;
        }

        return addOffset(baseTime, offsetAmount, unit);
    }

    private OffsetDateTime addOffset(OffsetDateTime base, long amount, String unit) {
        return switch (unit) {
            case "d" -> base.plus(amount, ChronoUnit.DAYS);
            case "h" -> base.plus(amount, ChronoUnit.HOURS);
            case "min" -> base.plus(amount, ChronoUnit.MINUTES);
            case "wk" -> base.plus(amount * 7, ChronoUnit.DAYS);
            case "mo" -> base.plusMonths(amount);
            case "a" -> base.plusYears(amount);
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }

}
