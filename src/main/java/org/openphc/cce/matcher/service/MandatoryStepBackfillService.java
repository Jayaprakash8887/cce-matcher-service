package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Backfill, one of the checks that follow a step instance's completion: the mandatory steps the journey
 * should already have recorded, and didn't, get step instances, so that skipping them still surfaces as
 * a deviation.
 *
 * <p>Progressive instantiation only works forward from a completed step, so a step created by its own
 * event leaves the mandatory steps that should have preceded it with no step instance at all. They read
 * as "not started" in the journey view and are invisible to the scheduler, so they never surface as a
 * deviation. Backfilled, they are driven DUE and then MISSED like any other step; if a late event does
 * arrive, it completes the backfilled step instance.
 */
@Service
@Transactional
public class MandatoryStepBackfillService {

    private static final Logger log = LoggerFactory.getLogger(MandatoryStepBackfillService.class);

    private final StepGroupService stepGroupService;
    private final StepInstanceScope stepInstanceScope;
    private final StepCreationService stepCreationService;

    public MandatoryStepBackfillService(StepGroupService stepGroupService,
                                        StepInstanceScope stepInstanceScope,
                                        StepCreationService stepCreationService) {
        this.stepGroupService = stepGroupService;
        this.stepInstanceScope = stepInstanceScope;
        this.stepCreationService = stepCreationService;
    }

    /**
     * Backfill the mandatory predecessors of the progress observed so far (see
     * {@link PlanDefinitionParser#computeMustPredecessorSteps}) that have no step instance where
     * {@code completedStepInstance} completed. Each is created not started, due at the completion that
     * revealed it.
     *
     * <p>Scoped to predecessors — work that is already late — and never to mandatory steps still
     * ahead in the chain. Backfilling those would stamp them with this completion's time and so
     * flatten the due dates their own {@code relatedAction} offsets define (e.g. lab-results' +3d
     * after lab-order); they are left to progressive instantiation, which creates them on their
     * predecessor's completion with the intended schedule.
     *
     * <p>Runs after the order-violation check deliberately: backfilled step instances must not count as
     * incomplete prerequisites for the completion that revealed them, so this does not invent an
     * ORDER_VIOLATION at completion time.
     *
     * <p>Idempotent: a mandatory step that already has a step instance where it counts — in any state,
     * whether pre-existing or created earlier in this same transaction by progressive instantiation — is
     * left alone. A backfilled step inside a group joins the instance
     * {@link StepGroupService#groupInstanceForWork} gives it.
     *
     * <p>A step also waits on what its enclosing groups wait on: a BP recorded in ANC visit 2, which follows
     * registration, backfills registration when it has no step instance, though the BP step names no
     * predecessor.
     */
    public void backfillMissingMandatorySteps(Enrollment enrollment, StepInstance completedStepInstance) {
        // The steps recorded so far, counting a step instance only where this completion's checks see it.
        Set<String> observedStepIds = enrollment.stepInstances().stream()
                .map(StepInstance::getActionId)
                .distinct()
                .filter(stepActionId -> !stepInstanceScope.stepInstancesInScope(enrollment, stepActionId,
                        completedStepInstance.getStepGroupInstance()).isEmpty())
                .collect(Collectors.toSet());

        // Their mandatory predecessors that have no step instance there.
        List<String> missingStepIds = PlanDefinitionParser
                .computeMustPredecessorSteps(observedStepIds, enrollment.protocol().steps(),
                        enrollment.protocol().groups(), enrollment.protocol().dependencyGraph()).stream()
                .filter(stepId -> !observedStepIds.contains(stepId))
                .sorted()
                .toList();

        if (missingStepIds.isEmpty()) {
            return;
        }

        // Anchor to the clinical time of the completion that revealed the gap. Every backfilled step
        // is a prerequisite that should already have happened, so they are all equally past due —
        // there is no future schedule left to preserve among them — and this keeps them on clinical
        // time rather than the ingestion clock.
        OffsetDateTime dueDate = completedStepInstance.getCompletedAt() != null
                ? completedStepInstance.getCompletedAt()
                : OffsetDateTime.now(ZoneOffset.UTC);

        for (String missingStepId : missingStepIds) {
            backfill(enrollment, completedStepInstance, missingStepId, dueDate);
        }
    }

    /** Create the step instance of {@code missingStepId}, a mandatory step the journey skipped. */
    private void backfill(Enrollment enrollment, StepInstance completedStepInstance, String missingStepId,
                          OffsetDateTime dueDate) {
        PlanDefinitionParser.StepMetadata missingStepMetadata = enrollment.step(missingStepId);

        StepGroupInstance groupInstance = null;
        if (missingStepMetadata != null && missingStepMetadata.groupId() != null) {
            // In a group: the group instance this work goes to, opened when the group has none yet.
            Optional<StepGroupInstance> groupInstanceForWork = stepGroupService.groupInstanceForWork(enrollment,
                    missingStepMetadata.groupId(), completedStepInstance.getStepGroupInstance(), dueDate);
            if (groupInstanceForWork.isEmpty()) {
                return; // a calendar-day group with no cycle yet: only an event of its own opens one
            }
            groupInstance = groupInstanceForWork.get();
            if (StepInstanceScope.hasStepInstance(enrollment, missingStepId, groupInstance)) {
                return; // opening its group created it, as one of the group's first steps
            }
        }

        // tolerance-days is the single grace window: missedDate = dueDate + tolerance.
        OffsetDateTime missedDate = missingStepMetadata != null && missingStepMetadata.toleranceDays() != null
                ? dueDate.plusDays(missingStepMetadata.toleranceDays())
                : null;

        // One step instance (repeatIndex 0) regardless of the step's timing.repeat count: this is a
        // placeholder for work that was never recorded, not a scheduled recurrence.
        enrollment.add(stepCreationService.createStep(enrollment.protocolInstance(), missingStepId, 0, dueDate,
                missedDate, "must", groupInstance));

        log.info("Backfilled unrecorded mandatory step {} as PENDING due at {} "
                        + "(revealed by completion of {}, instanceId={})",
                missingStepId, dueDate, completedStepInstance.getActionId(), enrollment.protocolInstance().getId());
    }
}
