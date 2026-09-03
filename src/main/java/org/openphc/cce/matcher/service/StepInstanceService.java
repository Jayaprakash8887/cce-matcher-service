package org.openphc.cce.matcher.service;

import org.openphc.cce.common.sla.SlaThresholdReader;
import org.openphc.cce.common.deviation.DeviationRecorder;
import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.history.StateTransitionHistoryWriter;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               ParsedProtocolCache parsedProtocolCache,
                               DeviationRecorder deviationRecorder,
                               IntelligenceActionEvaluator intelligenceActionEvaluator,
                               StateTransitionHistoryWriter stateTransitionHistoryWriter,
                               StepSlaScheduleService slaScheduleService,
                               SlaThresholdReader slaThresholdReader) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.parsedProtocolCache = parsedProtocolCache;
        this.deviationRecorder = deviationRecorder;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.stateTransitionHistoryWriter = stateTransitionHistoryWriter;
        this.slaScheduleService = slaScheduleService;
        this.slaThresholdReader = slaThresholdReader;
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
     * is null — no threshold has fallen due, so there is nothing to judge yet. The Compliance Service
     * writes that column, never this service.
     *
     * <p>The two thresholds are not stored on the step. They are written as
     * {@code step_sla_state_transition} rows in this same transaction, so the step and its SLA schedule
     * commit or roll back together.
     */
    public StepInstance createStep(ProtocolInstance protocolInstance, String actionId,
                                   int repeatIndex, OffsetDateTime dueDate,
                                   OffsetDateTime missedDate, String requiredBehavior) {
        StepInstance step = StepInstance.builder()
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(repeatIndex)
                .stepStatus(StepStatus.NOT_STARTED)
                // sla_status stays null: no threshold has fallen due, so there is nothing to judge
                // yet. The Compliance Service is the only writer of that column.
                .requiredBehavior(requiredBehavior)
                .build();

        step = stepInstanceRepository.save(step);

        slaScheduleService.schedule(step, dueDate, missedDate);

        // Capture the initial NOT_STARTED status in append-only history.
        stateTransitionHistoryWriter.recordStepInstanceTransition(step, step.getCreatedAt());

        log.info("Created step instance: actionId={}, repeatIndex={}, instanceId={}, stepId={}, due={}, missed={}",
                actionId, repeatIndex, protocolInstance.getId(), step.getId(), dueDate, missedDate);

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
        // settle this step's own SLA: that is the Compliance Service's judgement, made on its next
        // sweep, by comparing the completed_at recorded below against each threshold.
        SlaThresholdReader.SlaThresholds thresholds = slaThresholdReader.getThresholds(step.getId());

        // sla_status is deliberately left alone. Recording that the work happened and judging whether
        // it was timely are different questions with different owners; writing both here is what used
        // to require a rule about which service may overwrite the other.
        step.setStepStatus(StepStatus.COMPLETED);
        step.setCompletedAt(completedAt);
        step.setMatchedEventId(matchedEventId);
        step.setCompletedBySource(completedBySource);

        stepInstanceRepository.save(step);

        // Capture the COMPLETED transition in append-only history.
        stateTransitionHistoryWriter.recordStepInstanceTransition(step, now);


        log.info("Completed step: stepId={}, actionId={}, completedAt={} (SLA judged by Compliance)",
                step.getId(), step.getActionId(), completedAt);

        // The flattened steps and the normalized relatedAction graph — all three passes below
        // traverse them.
        ProtocolDefinition protocolDef = step.getProtocolInstance().getProtocolDefinition();
        ParsedProtocolCache.ParsedProtocol protocol =
                parsedProtocolCache.get(protocolDef.getId(),
                        () -> protocolDef.getDefinition().toString());
        List<PlanDefinitionParser.StepMetadata> steps = protocol.steps();
        PlanDefinitionParser.DependencyGraph graph = protocol.dependencyGraph();

        // This instance's step rows, read once and threaded through all three passes.
        //
        // Mutable by design: each pass that creates a row appends it here, so a later pass sees work
        // created earlier in this same transaction. createDependentSteps relies on that for diamond
        // dependencies (a step waiting on both this completion and one of its other dependents), and
        // backfillMissingMandatorySteps relies on it to avoid re-creating a mandatory step that
        // progressive instantiation just materialized.
        List<StepInstance> siblings = new ArrayList<>(
                stepInstanceRepository.findByProtocolInstanceId(step.getProtocolInstance().getId()));

        detectOrderViolations(step, steps, graph, siblings);
        createDependentSteps(step, graph, siblings, thresholds);
        backfillMissingMandatorySteps(step, steps, graph, siblings);
    }

    /**
     * Find the first step still awaiting its event for a given protocol instance and actionId.
     * Returns null if none is outstanding.
     *
     * <p>Scoped by {@link StepStatus} alone, so a step whose SLA is already MISSED is still picked
     * up by a late event rather than a duplicate row being created for it.
     */
    @Transactional(readOnly = true)
    public StepInstance findActionableStep(UUID protocolInstanceId, String actionId) {
        List<StepInstance> steps = stepInstanceRepository
                .findByProtocolInstanceIdAndActionIdAndStepStatus(
                        protocolInstanceId, actionId, StepStatus.NOT_STARTED);
        return steps.isEmpty() ? null : steps.get(0);
    }

    /**
     * Whether a step is still genuinely outstanding: its event has not arrived and its SLA can
     * still move. Excludes SLAs already settled — MISSED (written off) and MET (an optional step
     * closed out).
     */
    private boolean isOutstandingStep(StepInstance step) {
        return step.getStepStatus() == StepStatus.NOT_STARTED && isLiveSlaStatus(step.getSlaStatus());
    }

    /**
     * Detect order violations: when a step completes, check whether any of its mandatory
     * ({@code requiredBehavior="must"}) immediate predecessors is still outstanding (see
     * {@link #isOutstanding}). The immediate predecessors of step X are the prerequisites the
     * normalized dependency graph records for it (see
     * {@link PlanDefinitionParser#buildDependencyGraph}).
     */
    private void detectOrderViolations(StepInstance completedStep,
                                       List<PlanDefinitionParser.StepMetadata> steps,
                                       PlanDefinitionParser.DependencyGraph graph,
                                       List<StepInstance> siblings) {
        String completedStepId = completedStep.getActionId();

        Set<String> mustStepIds = PlanDefinitionParser.mustStepIds(steps);

        // Immediate predecessors of this step, restricted to mandatory ones
        List<String> mustPredecessorIds = graph.predecessorsOf(completedStepId).stream()
                .distinct()
                .filter(mustStepIds::contains)
                .toList();

        if (mustPredecessorIds.isEmpty()) {
            return;
        }

        // Check if any must-predecessor steps are still in actionable (incomplete) states
        List<String> incompletePrerequisites = new ArrayList<>();
        for (String predecessorId : mustPredecessorIds) {
            boolean hasIncomplete = siblings.stream()
                    .filter(s -> predecessorId.equals(s.getActionId()))
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

            DeviationRecorder.DeviationResult result = deviationRecorder.recordDeviation(completedStep,
                    DeviationType.ORDER_VIOLATION, metadata);

            // Skip the warning + intelligence evaluation if this order violation was already
            // recorded (idempotent under redelivered / concurrent completion processing).
            if (result.created()) {
                log.warn("Order violation detected: step {} (actionId={}) completed while "
                                + "prerequisite steps {} are still incomplete",
                        completedStep.getId(), completedStepId, incompletePrerequisites);

                intelligenceActionEvaluator.evaluateOnDeviation(completedStep, result.deviation());
            }
        }
    }

    /**
     * Progressive step instantiation: when a step completes, create the PENDING steps that declare
     * it as a prerequisite, with due dates calculated from their own {@code relatedAction} offsets.
     *
     * <p>{@code relatedAction} is relative rather than forward — a step states how it sits against
     * another — so the forward direction needed here comes from the normalized graph (see
     * {@link PlanDefinitionParser#buildDependencyGraph}). The offset that positions a dependent step
     * is the one on the edge between it and this prerequisite, whichever end declared it.
     */
    private void createDependentSteps(StepInstance completedStep,
                                      PlanDefinitionParser.DependencyGraph graph,
                                      List<StepInstance> siblings,
                                      SlaThresholdReader.SlaThresholds completedThresholds) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Steps that depend on the just-completed step
        List<PlanDefinitionParser.StepDependency> dependents =
                graph.successorsOf(completedStep.getActionId());

        if (dependents.isEmpty()) {
            return;
        }

        for (PlanDefinitionParser.StepDependency dependent : dependents) {
            PlanDefinitionParser.StepMetadata targetStep = dependent.step();
            PlanDefinitionParser.RelatedStepInfo edge = dependent.edge();

            // Dedup guard: skip if an instance for this step already exists.
            // A target may already exist because it was created reactively by its own
            // trigger (createInitialStep) before its predecessor completed, or because a
            // redelivered event re-completed the predecessor. Without this guard, progressive
            // instantiation would create a duplicate step that later goes overdue/missed and
            // raises a spurious deviation.
            if (stepInstanceRepository.existsByProtocolInstanceIdAndActionId(
                    protocolInstance.getId(), targetStep.id())) {
                log.debug("Skipping progressive instantiation of {} — an instance for this step already exists (instanceId={})",
                        targetStep.id(), protocolInstance.getId());
                continue;
            }

            String requiredBehavior = targetStep.requiredBehavior();

            // Only pre-create PENDING instances for mandatory (must) steps. Optional steps
            // (could / unspecified) are not instantiated on predecessor completion:
            // we may never receive their events, and a dangling PENDING row would later be
            // driven to MISSED and raise a spurious deviation. When an optional step's
            // event does arrive, MatcherEngine.processMatch creates the instance on the fly
            // (createInitialStep) and completes it in the same transaction.
            if (!"must".equals(requiredBehavior)) {
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
                    graph.predecessorsOf(targetStep.id()), completedStep.getActionId(), siblings);
            if (blockingPrerequisite != null) {
                log.debug("Deferring progressive instantiation of {} — prerequisite {} is still in flight (instanceId={})",
                        targetStep.id(), blockingPrerequisite, protocolInstance.getId());
                continue;
            }

            // after-start: offset from when the prerequisite became active (its scheduled due date)
            // after-end / after (default): offset from when the prerequisite completed (completedAt)
            OffsetDateTime baseTime = "after-start".equals(edge.relationship())
                    ? completedThresholds.dueDate()
                    : completedStep.getCompletedAt();
            if (baseTime == null) {
                baseTime = completedStep.getCompletedAt();
            }
            OffsetDateTime dueDate = calculateDueDate(baseTime, edge);

            // Create step instances — if timing specifies recurring, create N instances
            int repeatCount = 1;
            java.math.BigDecimal repeatPeriod = null;
            String repeatPeriodUnit = null;
            if (targetStep.timing() != null) {
                PlanDefinitionParser.TimingInfo timing = targetStep.timing();
                if (timing.count() != null && timing.count() > 1) {
                    repeatCount = timing.count();
                    repeatPeriod = timing.period();
                    repeatPeriodUnit = timing.periodUnit();
                }
            }

            for (int i = 0; i < repeatCount; i++) {
                OffsetDateTime instanceDueDate = dueDate;
                if (i > 0 && repeatPeriod != null && repeatPeriodUnit != null) {
                    instanceDueDate = addOffset(dueDate, repeatPeriod.longValue() * i, repeatPeriodUnit);
                }

                // tolerance-days is now the single grace window: missedDate = dueDate + tolerance.
                OffsetDateTime instanceMissedDate = null;
                if (targetStep.toleranceDays() != null) {
                    instanceMissedDate = instanceDueDate.plusDays(targetStep.toleranceDays());
                }

                siblings.add(createStep(protocolInstance, targetStep.id(),
                        i, instanceDueDate, instanceMissedDate,
                        requiredBehavior));

                log.info("Progressive instantiation: created step {} (repeat {}/{}) due at {} (triggered by {}, relationship={})",
                        targetStep.id(), i, repeatCount, instanceDueDate,
                        completedStep.getActionId(), edge.relationship());
            }
        }
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
                                             String completedActionId,
                                             List<StepInstance> siblings) {
        for (String prerequisiteId : prerequisiteIds) {
            if (prerequisiteId.equals(completedActionId)) {
                continue;
            }
            boolean inFlight = siblings.stream()
                    .filter(s -> prerequisiteId.equals(s.getActionId()))
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
     * left alone.
     */
    private void backfillMissingMandatorySteps(StepInstance completedStep,
                                               List<PlanDefinitionParser.StepMetadata> steps,
                                               PlanDefinitionParser.DependencyGraph graph,
                                               List<StepInstance> siblings) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        Set<String> observedStepIds = siblings.stream()
                .map(StepInstance::getActionId)
                .collect(Collectors.toSet());

        List<String> missingMustSteps = PlanDefinitionParser
                .computeMustPredecessorSteps(observedStepIds, steps, graph).stream()
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

            OffsetDateTime missedDate = null;
            if (stepMetadata != null && stepMetadata.toleranceDays() != null) {
                missedDate = dueDate.plusDays(stepMetadata.toleranceDays());
            }

            // One instance (repeatIndex 0) regardless of the step's timing.repeat count: this is a
            // placeholder for work that was never recorded, not a scheduled recurrence.
            siblings.add(createStep(protocolInstance, stepId, 0, dueDate, missedDate, "must"));

            log.info("Backfilled unrecorded mandatory step {} as PENDING due at {} "
                            + "(revealed by completion of {}, instanceId={})",
                    stepId, dueDate, completedStep.getActionId(), protocolInstance.getId());
        }
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

    /**
     * Settle a step's SLA at the moment its event arrives.
     *
     * <p>{@link SlaStatus#MET} only when the event beat {@code dueDate}. Otherwise the SLA keeps
     * whatever it had already reached — {@code OVERDUE} past the due date, {@code MISSED} past the
     * missed date — so the row states plainly that the work was done, and done late.
     *
     * <p>Derived from {@code completedAt} rather than read off the row, because the event carries a
     * clinical occurrence time that may precede the scheduler's last sweep: an act that happened
     * before the due date but was reported after it is MET, not OVERDUE.
     */
}
