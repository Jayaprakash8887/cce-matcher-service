package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.repository.StepSlaStateTransitionRepository;
import org.openphc.cce.common.support.RequiredBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes each step's SLA schedule into {@code step_sla_state_transition}, and reads it back.
 *
 * <p>Each threshold is the {@code process_by} of one transition row rather than a column on the step.
 * That keeps the evaluating service's working set in a table that shrinks as work is processed, instead
 * of requiring a scan of every step row to re-derive what has already fired.
 *
 * <p>Only mandatory steps are scheduled. Every row in the table therefore belongs to a step the
 * protocol required, which is what makes a breach meaningful at all — see {@link #schedule}.
 *
 * <p>This service is <strong>create-only</strong> on the table: it never sets {@code is_processed},
 * {@code processed_at}, {@code attempts} or {@code next_attempt_at} after insert. Those belong to the
 * evaluating service, which keeps one writer per column.
 *
 * <p>Rows are written with {@link Propagation#MANDATORY} so they can only ever be created inside the
 * caller's transaction — a step and its schedule commit or roll back together, and there is no window
 * in which a step exists with no schedule (or a schedule with no step). The same holds for the
 * {@code MET_CONDITION_REACHED} row {@link #scheduleMetIfOnTime} adds at completion: it commits with
 * the {@code completed_at} it stands for.
 *
 * <p>Write-only. Reading a step's thresholds back is
 * {@link org.openphc.cce.common.sla.SlaThresholdReader}, shared with the Step SLA Service so the
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
     * <p>Neither does an optional step, whatever thresholds it was created with. A deadline is the
     * point at which required work has not been recorded, and nothing is required of an optional step —
     * so it can be neither {@code OVERDUE} nor {@code MISSED}, and a row for one only schedules a
     * judgement the Step SLA Service must then decline to make. This is the enforcement that holds:
     * {@code PlanDefinitionParser.validateOptionalStepDeadlines} rejects such a protocol at load, but
     * protocols loaded before that check existed are already in the database, and their steps come
     * through here.
     *
     * <p>Optional is {@code requiredBehavior != "must"}, including absent — the same reading
     * progressive instantiation takes, so a step is not required by one rule and optional by the next.
     *
     * @param step       the step being created, already persisted so it has an id
     * @param dueDate    when the step should go {@code OVERDUE}, or null
     * @param missedDate when the step should be written off as {@code MISSED}, or null
     */
    public void schedule(StepInstance step, OffsetDateTime dueDate, OffsetDateTime missedDate) {
        if (!RequiredBehavior.isMandatory(step.getRequiredBehavior())) {
            if (dueDate != null || missedDate != null) {
                log.warn("Step {} (actionId={}) is optional (requiredBehavior={}) — its deadlines "
                                + "(due={}, missed={}) schedule nothing: an optional step cannot go "
                                + "OVERDUE or MISSED",
                        step.getId(), step.getActionId(), step.getRequiredBehavior(), dueDate, missedDate);
            }
            return;
        }

        List<StepSlaStateTransition> rows = new ArrayList<>(2);
        addIfScheduled(rows, step, SlaTransitionType.DUE_DATE_REACHED, dueDate);
        addIfScheduled(rows, step, SlaTransitionType.MISSED_DATE_REACHED, missedDate);

        if (rows.isEmpty()) {
            log.debug("Step {} (actionId={}) has no SLA thresholds — nothing scheduled",
                    step.getId(), step.getActionId());
            return;
        }

        transitionRepository.saveAll(rows);
        log.debug("Scheduled {} SLA transition(s) for step {} (actionId={}, due={}, missed={})",
                rows.size(), step.getId(), step.getActionId(), dueDate, missedDate);
    }

    /**
     * Record that a step's work was recorded before its due date, as a {@code MET_CONDITION_REACHED}
     * row the Step SLA Service applies on its next cycle.
     *
     * <p>Unlike the deadlines this is not known when the step is created, so it is written here, at the
     * completion. {@code process_by} is the {@code completed_at} that satisfied it — the moment the
     * condition was reached, exactly as the other rows carry the moment their threshold falls — so the
     * row is due at once and the verdict lands within a poll interval instead of waiting for a due date
     * that may be weeks away.
     *
     * <p>Whether the step was on time is still the Step SLA Service's call: this only schedules the
     * question. The check here decides whether there is anything to ask, and it asks nothing when the
     * work was late — the step's {@code DUE_DATE_REACHED} row is already scheduled to catch that.
     *
     * <p>Mandatory steps only, like every other row. An optional step has no deadline, so there is no
     * due date it can be said to have beaten.
     *
     * @param step        the step just completed, with its {@code completed_at} already set
     * @param completedAt the clinical time the work was recorded at
     */
    public void scheduleMetIfOnTime(StepInstance step, OffsetDateTime completedAt) {
        if (!RequiredBehavior.isMandatory(step.getRequiredBehavior())) {
            return;
        }
        if (step.getDueDate() == null || completedAt == null || !completedAt.isBefore(step.getDueDate())) {
            return;
        }

        transitionRepository.save(StepSlaStateTransition.builder()
                .stepInstance(step)
                .transitionType(SlaTransitionType.MET_CONDITION_REACHED)
                .processBy(completedAt)
                .nextAttemptAt(completedAt)
                .build());

        log.debug("Step {} (actionId={}) was recorded at {}, before its due date of {} — MET scheduled",
                step.getId(), step.getActionId(), completedAt, step.getDueDate());
    }

    private void addIfScheduled(List<StepSlaStateTransition> rows, StepInstance step,
                                SlaTransitionType type, OffsetDateTime processBy) {
        if (processBy == null) {
            return;
        }
        rows.add(StepSlaStateTransition.builder()
                .stepInstance(step)
                .transitionType(type)
                .processBy(processBy)
                .nextAttemptAt(processBy)
                .build());
    }
}
