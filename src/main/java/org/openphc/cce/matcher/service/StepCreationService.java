package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.history.StateTransitionHistoryWriter;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserting {@code step_instance} rows, with their SLA schedule and first history entry.
 *
 * <p>Deciding which steps to create is left to the callers: {@link ProgressiveInstantiationService} and
 * {@link MandatoryStepBackfillService} as steps complete, {@link StepGroupService} as group instances open,
 * and {@link StepInstanceService} for a step an event brings into being.
 */
@Service
@Transactional
public class StepCreationService {

    private static final Logger log = LoggerFactory.getLogger(StepCreationService.class);

    private final StepInstanceRepository stepInstanceRepository;
    private final StepSlaScheduleService slaScheduleService;
    private final StateTransitionHistoryWriter stateTransitionHistoryWriter;

    public StepCreationService(StepInstanceRepository stepInstanceRepository,
                               StepSlaScheduleService slaScheduleService,
                               StateTransitionHistoryWriter stateTransitionHistoryWriter) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.slaScheduleService = slaScheduleService;
        this.stateTransitionHistoryWriter = stateTransitionHistoryWriter;
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
     * step inside a step group, attached to the group instance it belongs to.
     *
     * @param stepGroupInstance the instance of the step's nearest group, or null outside any group
     */
    public StepInstance createStep(ProtocolInstance protocolInstance, String stepActionId,
                                   int repeatIndex, OffsetDateTime dueDate,
                                   OffsetDateTime missedDate, String requiredBehavior,
                                   StepGroupInstance stepGroupInstance) {
        StepInstance stepInstance = StepInstance.builder()
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

        stepInstance = stepInstanceRepository.save(stepInstance);

        slaScheduleService.schedule(stepInstance, dueDate, missedDate);

        // Capture the initial NOT_STARTED status in append-only history.
        stateTransitionHistoryWriter.recordStepInstanceTransition(stepInstance, stepInstance.getCreatedAt());

        log.info("Created step instance: actionId={}, repeatIndex={}, instanceId={}, stepId={}, due={}, missed={}",
                stepActionId, repeatIndex, protocolInstance.getId(), stepInstance.getId(), dueDate, missedDate);

        return stepInstance;
    }

    /**
     * Create a step's rows, attached to {@code stepGroupInstance}: one row, or {@code count} rows a
     * {@code period} apart when the step repeats on its own {@code timing}. tolerance-days is the single
     * grace window: missedDate = dueDate + tolerance.
     *
     * <p>A step created with no due date gets one row and no deadline. That happens only inside a group
     * instance whose planned due date is not known yet.
     */
    public List<StepInstance> createStepRows(ProtocolInstance protocolInstance,
                                             PlanDefinitionParser.StepMetadata stepMetadata,
                                             OffsetDateTime dueDate, StepGroupInstance stepGroupInstance) {
        int repeatCount = 1;
        BigDecimal repeatPeriod = null;
        String repeatPeriodUnit = null;
        PlanDefinitionParser.TimingInfo timing = stepMetadata.timing();
        // Repeats on its own timing only when there is a due date to count the repeats from.
        if (dueDate != null && timing != null && timing.count() != null && timing.count() > 1) {
            repeatCount = timing.count();
            repeatPeriod = timing.period();
            repeatPeriodUnit = timing.periodUnit();
        }

        List<StepInstance> createdStepInstances = new ArrayList<>(repeatCount);
        for (int i = 0; i < repeatCount; i++) {
            OffsetDateTime instanceDueDate = dueDate;
            if (i > 0 && repeatPeriod != null && repeatPeriodUnit != null) {
                instanceDueDate = addOffset(dueDate, repeatPeriod.longValue() * i, repeatPeriodUnit);
            }

            OffsetDateTime instanceMissedDate = null;
            if (instanceDueDate != null && stepMetadata.toleranceDays() != null) {
                instanceMissedDate = instanceDueDate.plusDays(stepMetadata.toleranceDays());
            }

            createdStepInstances.add(createStep(protocolInstance, stepMetadata.id(), i, instanceDueDate,
                    instanceMissedDate, stepMetadata.requiredBehavior(), stepGroupInstance));
        }
        return createdStepInstances;
    }

    /** {@code baseTime} moved by {@code edge}'s offset; {@code baseTime} itself when the edge has none. */
    static OffsetDateTime calculateDueDate(OffsetDateTime baseTime, PlanDefinitionParser.RelatedStepInfo edge) {
        if (edge.offsetValue() == null || edge.offsetUnit() == null) {
            return baseTime;
        }
        return addOffset(baseTime, edge.offsetValue().longValue(), edge.offsetUnit());
    }

    private static OffsetDateTime addOffset(OffsetDateTime base, long amount, String unit) {
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
