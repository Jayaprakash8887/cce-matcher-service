package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.repository.StepSlaStateTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes each step's SLA schedule into {@code step_sla_state_transition}, and reads it back.
 *
 * <p>Each threshold is the {@code process_by} of one transition row rather than a column on the step.
 * That keeps the evaluating service's working set in a table that shrinks as work is processed, instead
 * of requiring a scan of every step row to re-derive what has already fired.
 *
 * <p>This service is <strong>create-only</strong> on the table: it never sets {@code is_processed},
 * {@code processed_at}, {@code attempts} or {@code next_attempt_at} after insert. Those belong to the
 * evaluating service, which keeps one writer per column.
 *
 * <p>Rows are written with {@link Propagation#MANDATORY} so they can only ever be created inside the
 * caller's transaction — a step and its schedule commit or roll back together, and there is no window
 * in which a step exists with no schedule (or a schedule with no step).
 *
 * <p>Write-only. Reading a step's thresholds back is
 * {@link org.openphc.cce.common.service.SlaThresholdReader}, shared with the Compliance Service so the
 * two cannot drift on how a schedule is interpreted.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StepSlaScheduleService {

    private static final Logger log = LoggerFactory.getLogger(StepSlaScheduleService.class);

    private final StepSlaStateTransitionRepository transitionRepository;

    public StepSlaScheduleService(StepSlaStateTransitionRepository transitionRepository) {
        this.transitionRepository = transitionRepository;
    }


    /**
     * Schedule the transitions a freshly created step can undergo.
     *
     * <p>A threshold that is absent gets no row: there is nothing for the evaluator to fire, and an
     * absent deadline is precisely what "this step cannot go overdue" means.
     *
     * @param step       the step being created, already persisted so it has an id
     * @param dueDate    when the step should go {@code OVERDUE}, or null
     * @param missedDate when the step should be written off as {@code MISSED}, or null
     */
    public void schedule(StepInstance step, OffsetDateTime dueDate, OffsetDateTime missedDate) {
        List<StepSlaStateTransition> rows = new ArrayList<>(2);
        addIfScheduled(rows, step.getId(), SlaTransitionType.DUE_DATE_REACHED, dueDate);
        addIfScheduled(rows, step.getId(), SlaTransitionType.MISSED_DATE_REACHED, missedDate);

        if (rows.isEmpty()) {
            log.debug("Step {} (actionId={}) has no SLA thresholds — nothing scheduled",
                    step.getId(), step.getActionId());
            return;
        }

        transitionRepository.saveAll(rows);
        log.debug("Scheduled {} SLA transition(s) for step {} (actionId={}, due={}, missed={})",
                rows.size(), step.getId(), step.getActionId(), dueDate, missedDate);
    }

    private void addIfScheduled(List<StepSlaStateTransition> rows, UUID stepInstanceId,
                                SlaTransitionType type, OffsetDateTime processBy) {
        if (processBy == null) {
            return;
        }
        rows.add(StepSlaStateTransition.builder()
                .stepInstanceId(stepInstanceId)
                .transitionType(type)
                .processBy(processBy)
                .nextAttemptAt(processBy)
                .build());
    }
}
