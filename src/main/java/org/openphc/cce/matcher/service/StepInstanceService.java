package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.history.StateTransitionHistoryWriter;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.common.sla.SlaThresholdReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * The core of the matcher: which step instance an event completes, and completing it.
 *
 * <p>For a step in a group, the group instance the event goes to is chosen first (see
 * {@link StepGroupService#groupInstanceForEvent}), then the step's instance in it. Completing a step
 * instance then runs what follows from it, each in its own class: the order-violation check
 * ({@link OrderViolationDetector}), progressive instantiation ({@link ProgressiveInstantiationService})
 * and backfill ({@link MandatoryStepBackfillService}).
 */
@Service
@Transactional
public class StepInstanceService {

    private static final Logger log = LoggerFactory.getLogger(StepInstanceService.class);

    private final StepInstanceRepository stepInstanceRepository;
    private final ParsedProtocolCache parsedProtocolCache;
    private final StateTransitionHistoryWriter stateTransitionHistoryWriter;
    private final StepSlaScheduleService slaScheduleService;
    private final SlaThresholdReader slaThresholdReader;
    private final ProtocolInstanceService protocolInstanceService;
    private final StepGroupService stepGroupService;
    private final StepCreationService stepCreationService;
    private final OrderViolationDetector orderViolationDetector;
    private final ProgressiveInstantiationService progressiveInstantiationService;
    private final MandatoryStepBackfillService mandatoryStepBackfillService;

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               ParsedProtocolCache parsedProtocolCache,
                               StateTransitionHistoryWriter stateTransitionHistoryWriter,
                               StepSlaScheduleService slaScheduleService,
                               SlaThresholdReader slaThresholdReader,
                               ProtocolInstanceService protocolInstanceService,
                               StepGroupService stepGroupService,
                               StepCreationService stepCreationService,
                               OrderViolationDetector orderViolationDetector,
                               ProgressiveInstantiationService progressiveInstantiationService,
                               MandatoryStepBackfillService mandatoryStepBackfillService) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.parsedProtocolCache = parsedProtocolCache;
        this.stateTransitionHistoryWriter = stateTransitionHistoryWriter;
        this.slaScheduleService = slaScheduleService;
        this.slaThresholdReader = slaThresholdReader;
        this.protocolInstanceService = protocolInstanceService;
        this.stepGroupService = stepGroupService;
        this.stepCreationService = stepCreationService;
        this.orderViolationDetector = orderViolationDetector;
        this.progressiveInstantiationService = progressiveInstantiationService;
        this.mandatoryStepBackfillService = mandatoryStepBackfillService;
    }

    // ── Which step instance an event completes ──

    /**
     * The step instance an event for {@code stepActionId} completes: the one awaiting it, or a new one
     * when none is.
     * <ul>
     *   <li>A step in a group: first the group instance the event goes to, under the enrollment's lock (see
     *       {@link StepGroupService#groupInstanceForEvent}), then the step's open instance there. An earlier
     *       cycle's open instance is never completed, whatever the event's clinical time.</li>
     *   <li>A step outside any group: its open instance in the enrollment.</li>
     * </ul>
     * Of several open instances, the event completes the one whose due date is nearest its clinical time.
     *
     * <p>Opening a group instance creates its first mandatory steps, except the matched step: created with
     * the rest, it would take the instance's due date, which is often unknown when an event opens it, and
     * so would never be judged. The event creates it instead (see {@link #createStepInstanceForEvent}).
     *
     * @param now        the event's single clock reading: the due date of a step instance the event creates
     * @param occurredAt the event's clinical time: which open instance it completes, which day it falls on,
     *                   and the opening time of any group instance it opens
     */
    public StepInstance stepInstanceForEvent(ProtocolInstance protocolInstance, String stepActionId,
                                             OffsetDateTime now, OffsetDateTime occurredAt) {
        ParsedProtocolCache.ParsedProtocol protocol = parsedProtocol(protocolInstance);
        PlanDefinitionParser.StepMetadata matchedStepMetadata = protocol.step(stepActionId);
        if (matchedStepMetadata == null || matchedStepMetadata.groupId() == null) {
            // Outside any group, or an action the protocol doesn't declare: there is no group instance to
            // choose, so the step's open instance is looked for across the enrollment.
            return openStepInstance(protocolInstance, stepActionId, occurredAt)
                    .orElseGet(() -> createStepInstanceForEvent(protocolInstance, stepActionId, matchedStepMetadata,
                            now, null));
        }

        // In a group: under the enrollment's lock, first the group instance the event goes to, then the
        // step's open instance in it.
        Enrollment enrollment = lockAndLoad(protocolInstance, protocol);
        StepGroupInstance groupInstance =
                stepGroupService.groupInstanceForEvent(enrollment, matchedStepMetadata, occurredAt);
        return openStepInstanceIn(enrollment, groupInstance, stepActionId, occurredAt)
                .orElseGet(() -> createStepInstanceForEvent(protocolInstance, stepActionId, matchedStepMetadata,
                        now, groupInstance));
    }

    /**
     * {@code stepActionId}'s open step instance in the enrollment, for a step outside any group. Open means
     * its event has not arrived, whatever its SLA: a step whose SLA is already MISSED is still picked up by
     * a late event rather than a duplicate being created for it.
     */
    private Optional<StepInstance> openStepInstance(ProtocolInstance protocolInstance, String stepActionId,
                                                    OffsetDateTime occurredAt) {
        return stepInstanceRepository.findByProtocolInstanceIdAndActionIdAndStepStatus(
                        protocolInstance.getId(), stepActionId, StepStatus.NOT_STARTED).stream()
                .min(nearestDueDate(occurredAt));
    }

    /** {@code stepActionId}'s open step instance in {@code groupInstance} itself. */
    private static Optional<StepInstance> openStepInstanceIn(Enrollment enrollment, StepGroupInstance groupInstance,
                                                             String stepActionId, OffsetDateTime occurredAt) {
        return enrollment.stepInstances().stream()
                .filter(stepInstance -> stepActionId.equals(stepInstance.getActionId())
                        && stepInstance.getStepStatus() == StepStatus.NOT_STARTED
                        && StepInstanceScope.isDirectlyIn(stepInstance, groupInstance))
                .min(nearestDueDate(occurredAt));
    }

    /**
     * Open step instances in the order an event at {@code occurredAt} takes them: the due date nearest the
     * event's clinical time first, ties to the earlier due date; instances with no due date after dated
     * ones, oldest first.
     */
    private static Comparator<StepInstance> nearestDueDate(OffsetDateTime occurredAt) {
        return Comparator.<StepInstance, Boolean>comparing(stepInstance -> stepInstance.getDueDate() == null)
                .thenComparingLong(stepInstance -> stepInstance.getDueDate() == null
                        ? 0L : Math.abs(Duration.between(occurredAt, stepInstance.getDueDate()).toMillis()))
                .thenComparing(StepInstance::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StepInstance::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * The step instance an event brings into being when none awaits it: one instance, due at {@code now},
     * the event's single clock reading, and missed {@code tolerance-days} after that. An action the
     * protocol doesn't declare, which an explicit match can name, gets a bare step instance with no
     * deadline.
     */
    private StepInstance createStepInstanceForEvent(ProtocolInstance protocolInstance, String stepActionId,
                                                    PlanDefinitionParser.StepMetadata matchedStepMetadata,
                                                    OffsetDateTime now, StepGroupInstance groupInstance) {
        if (matchedStepMetadata == null) {
            // An explicit match can name an action the protocol doesn't declare: a bare step instance, with
            // no deadline.
            return stepCreationService.createStep(protocolInstance, stepActionId, 0, now, null, null);
        }
        // tolerance-days is the single grace window: missedDate = dueDate + tolerance.
        OffsetDateTime missedDate = matchedStepMetadata.toleranceDays() != null
                ? now.plusDays(matchedStepMetadata.toleranceDays())
                : null;
        return stepCreationService.createStep(protocolInstance, matchedStepMetadata.id(), 0, now, missedDate,
                matchedStepMetadata.requiredBehavior(), groupInstance);
    }

    // ── Completing a step instance ──

    /**
     * Complete a step instance: record that its event arrived, then run what follows from it, in order:
     * <ol>
     *   <li>the order-violation check ({@link OrderViolationDetector});</li>
     *   <li>progressive instantiation: the steps and groups that follow it
     *       ({@link ProgressiveInstantiationService});</li>
     *   <li>backfill: the mandatory steps the journey skipped ({@link MandatoryStepBackfillService}). It
     *       runs last on purpose: a backfilled step instance must not count as an incomplete prerequisite
     *       of the completion that revealed it, nor be created for a step that progressive instantiation
     *       has just created.</li>
     * </ol>
     *
     * <p>Completability depends only on {@link StepStatus} — a step whose SLA is already
     * {@link SlaStatus#MISSED} can still be completed by a late event. The step instance then records
     * both facts: the event arrived, and the SLA was missed.
     */
    public void completeStepInstance(StepInstance stepInstance, UUID matchedEventId, String completedBySource,
                                     OffsetDateTime occurredAt) {
        if (stepInstance.getStepStatus() != StepStatus.NOT_STARTED) {
            // A step instance completes once. Refusing a second completion keeps what follows it, an order
            // violation among them, to a single run.
            throw new IllegalStateException(
                    "Cannot complete step with stepStatus " + stepInstance.getStepStatus() + ": " + stepInstance.getId());
        }

        // Read once, to anchor an after-start dependent to when this step became active. Not used to
        // settle this step's own SLA: that is the Step SLA Service's judgement, made on its next
        // sweep, by comparing the completed_at recorded below against each threshold.
        SlaThresholdReader.SlaThresholds slaThresholds = slaThresholdReader.getThresholds(stepInstance.getId());

        recordCompletion(stepInstance, matchedEventId, completedBySource, occurredAt);

        // What follows may open a step group instance, so it works on the enrollment as read under its lock.
        ProtocolInstance protocolInstance = stepInstance.getProtocolInstance();
        Enrollment enrollment = lockAndLoad(protocolInstance, parsedProtocol(protocolInstance));

        orderViolationDetector.detectOrderViolations(enrollment, stepInstance);
        progressiveInstantiationService.instantiateDependents(enrollment, stepInstance, slaThresholds);
        mandatoryStepBackfillService.backfillMissingMandatorySteps(enrollment, stepInstance);
    }

    /**
     * Record that {@code stepInstance}'s event arrived, and schedule the judgement of whether it was on
     * time.
     */
    private void recordCompletion(StepInstance stepInstance, UUID matchedEventId, String completedBySource,
                                  OffsetDateTime occurredAt) {
        // completedAt is the clinical occurrence time (when the act happened), not the ingestion
        // time — so dependent steps' due/missed dates and the SLA outcome reflect real-world timing
        // rather than how long the event took to reach us. Clamp to now(): a step cannot have
        // completed in the future, and a bad/future source clock must not push downstream schedules
        // out. Fall back to now() when no occurrence time was resolved.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime completedAt = (occurredAt != null && !occurredAt.isAfter(now)) ? occurredAt : now;

        // sla_status is deliberately left alone. Recording that the work happened and judging whether
        // it was timely are different questions with different owners; this service only schedules the
        // second one (below), and writing both here is what used to require a rule about which service
        // may overwrite the other.
        stepInstance.setStepStatus(StepStatus.COMPLETED);
        stepInstance.setCompletedAt(completedAt);
        stepInstance.setMatchedEventId(matchedEventId);
        stepInstance.setCompletedBySource(completedBySource);

        stepInstanceRepository.save(stepInstance);

        // Work recorded by the deadline is a verdict waiting to be written, so schedule it now.
        // The Step SLA Service takes the row on its next cycle; without it, a step completed weeks
        // early would sit unjudged until its due date arrived.
        slaScheduleService.scheduleMetIfOnTime(stepInstance, completedAt);

        // Capture the COMPLETED transition in append-only history.
        stateTransitionHistoryWriter.recordStepInstanceTransition(stepInstance, now);

        log.info("Completed step: stepId={}, actionId={}, completedAt={} (SLA judged by the Step SLA Service)",
                stepInstance.getId(), stepInstance.getActionId(), completedAt);
    }

    // ── The enrollment ──

    /**
     * The enrollment as the work for one event sees it: locked first when its protocol has step groups
     * (see {@link ProtocolInstanceService#lockForStepGroups}), then its step instances read once.
     */
    private Enrollment lockAndLoad(ProtocolInstance protocolInstance, ParsedProtocolCache.ParsedProtocol protocol) {
        protocolInstanceService.lockForStepGroups(protocolInstance, protocol);
        return new Enrollment(protocolInstance, protocol,
                new ArrayList<>(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId())));
    }

    private ParsedProtocolCache.ParsedProtocol parsedProtocol(ProtocolInstance protocolInstance) {
        ProtocolDefinition protocolDef = protocolInstance.getProtocolDefinition();
        return parsedProtocolCache.get(protocolDef.getId(), () -> protocolDef.getDefinition().toString());
    }
}
