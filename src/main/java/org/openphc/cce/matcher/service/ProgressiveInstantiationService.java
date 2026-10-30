package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.sla.SlaThresholdReader;
import org.openphc.cce.common.support.RequiredBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Progressive instantiation: when a step instance completes, create what follows it. The mandatory steps
 * that declare it as a prerequisite get step instances, due by their own {@code relatedAction} offsets,
 * and the mandatory groups that follow it open (see {@link StepGroupService#openFollowingGroup}).
 *
 * <p>{@code relatedAction} is relative rather than forward — a step states how it sits against
 * another — so the forward direction needed here comes from the normalized graph (see
 * {@link PlanDefinitionParser#buildDependencyGraph}). The offset that positions a dependent step
 * is the one on the edge between it and this prerequisite, whichever end declared it.
 */
@Service
@Transactional
public class ProgressiveInstantiationService {

    private static final Logger log = LoggerFactory.getLogger(ProgressiveInstantiationService.class);

    private final StepGroupService stepGroupService;
    private final StepInstanceScope stepInstanceScope;
    private final StepCreationService stepCreationService;

    public ProgressiveInstantiationService(StepGroupService stepGroupService,
                                           StepInstanceScope stepInstanceScope,
                                           StepCreationService stepCreationService) {
        this.stepGroupService = stepGroupService;
        this.stepInstanceScope = stepInstanceScope;
        this.stepCreationService = stepCreationService;
    }

    /**
     * Create what follows {@code completedStepInstance}: step instances for its dependent steps, then the
     * first instances of its dependent groups.
     *
     * @param completedThresholds the completed step instance's SLA thresholds: an {@code after-start}
     *                            dependent counts from its due date, when the step became active
     */
    public void instantiateDependents(Enrollment enrollment, StepInstance completedStepInstance,
                                      SlaThresholdReader.SlaThresholds completedThresholds) {
        PlanDefinitionParser.DependencyGraph graph = enrollment.protocol().dependencyGraph();

        // after-start: offset from when the prerequisite became active (its scheduled due date)
        // after-end / after (default): offset from when the prerequisite completed (completedAt)
        OffsetDateTime startedAt = completedThresholds.dueDate() != null
                ? completedThresholds.dueDate()
                : completedStepInstance.getCompletedAt();

        for (PlanDefinitionParser.StepDependency stepDependency
                : graph.successorsOf(completedStepInstance.getActionId())) {
            createDependentStepInstances(enrollment, completedStepInstance, stepDependency, startedAt);
        }
        for (PlanDefinitionParser.GroupDependency groupDependency
                : graph.groupSuccessorsOf(completedStepInstance.getActionId())) {
            stepGroupService.openFollowingGroup(enrollment, completedStepInstance, groupDependency.group());
        }
    }

    /**
     * Create the step instances of the step {@code stepDependency} names, which follows
     * {@code completedStepInstance}. Nothing is created when:
     * <ul>
     *   <li>the step is optional ({@code could} or unspecified): its event may never arrive, and a dangling
     *       step instance would later be driven to MISSED and raise a spurious deviation. When its event
     *       does arrive, the event creates its step instance;</li>
     *   <li>another of its prerequisites is still in flight: that prerequisite's completion runs this
     *       again, so the due date counts from the last prerequisite to finish rather than whichever
     *       happened to complete first;</li>
     *   <li>its group has no instance it can join: a {@code calendar-day} group opens only for an event of
     *       its own;</li>
     *   <li>it already has a step instance where it would go. Its own event may have created one before
     *       this prerequisite completed, a redelivered event may have completed the prerequisite again, or
     *       opening its group may just have created it. A step in a group is looked for in that group
     *       instance only, so another cycle of the group creates it again.</li>
     * </ul>
     */
    private void createDependentStepInstances(Enrollment enrollment, StepInstance completedStepInstance,
                                              PlanDefinitionParser.StepDependency stepDependency,
                                              OffsetDateTime startedAt) {
        PlanDefinitionParser.StepMetadata dependentStepMetadata = stepDependency.step();
        PlanDefinitionParser.RelatedStepInfo edge = stepDependency.edge();

        if (!RequiredBehavior.isMandatory(dependentStepMetadata.requiredBehavior())) {
            // Optional: its event may never come, and a pending instance would go MISSED for nothing.
            log.debug("Skipping progressive instantiation of non-mandatory step {} "
                            + "(requiredBehavior={}, instanceId={})",
                    dependentStepMetadata.id(), dependentStepMetadata.requiredBehavior(),
                    enrollment.protocolInstance().getId());
            return;
        }

        String blockingPrerequisite =
                firstInFlightPrerequisite(enrollment, dependentStepMetadata, completedStepInstance);
        if (blockingPrerequisite != null) {
            // Wait for it: its completion runs this again, so the due date counts from the last to finish.
            log.debug("Deferring progressive instantiation of {} — prerequisite {} is still in flight (instanceId={})",
                    dependentStepMetadata.id(), blockingPrerequisite, enrollment.protocolInstance().getId());
            return;
        }

        StepGroupInstance groupInstance = null;
        if (dependentStepMetadata.groupId() != null) {
            // In a group: the group instance this work goes to, opened when the group has none yet.
            Optional<StepGroupInstance> groupInstanceForWork = stepGroupService.groupInstanceForWork(enrollment,
                    dependentStepMetadata.groupId(), completedStepInstance.getStepGroupInstance(),
                    completedStepInstance.getCompletedAt());
            if (groupInstanceForWork.isEmpty()) {
                // A calendar-day group with no cycle yet: only an event of its own opens one.
                log.debug("Skipping progressive instantiation of {} — its calendar-day group has no cycle yet "
                        + "(instanceId={})", dependentStepMetadata.id(), enrollment.protocolInstance().getId());
                return;
            }
            groupInstance = groupInstanceForWork.get();
        }

        if (StepInstanceScope.hasStepInstance(enrollment, dependentStepMetadata.id(), groupInstance)) {
            // Already there: from its own event, a redelivered event, or its group opening just now.
            log.debug("Skipping progressive instantiation of {} — an instance for this step already exists "
                    + "(instanceId={})", dependentStepMetadata.id(), enrollment.protocolInstance().getId());
            return;
        }

        // after-start counts from when the completed step became due, anything else from its completion.
        OffsetDateTime baseTime = "after-start".equals(edge.relationship())
                ? startedAt
                : completedStepInstance.getCompletedAt();
        OffsetDateTime dueDate = StepCreationService.calculateDueDate(baseTime, edge);
        for (StepInstance createdStepInstance : stepCreationService.createStepRows(enrollment.protocolInstance(),
                dependentStepMetadata, dueDate, groupInstance)) {
            enrollment.add(createdStepInstance);
            log.info("Progressive instantiation: created step {} (repeat {}) due at {} (triggered by {}, relationship={})",
                    dependentStepMetadata.id(), createdStepInstance.getRepeatIndex(),
                    createdStepInstance.getDueDate(), completedStepInstance.getActionId(), edge.relationship());
        }
    }

    /**
     * The first prerequisite of {@code dependentStepMetadata}'s step, other than the one that just
     * completed, that still has an outstanding step instance where the step completed — or null when none
     * does.
     *
     * <p>A prerequisite with no step instance at all does not block: it may never be recorded, and
     * waiting on it would strand the dependent step forever. Neither does one whose SLA has already
     * settled, which will never complete later than now.
     */
    private String firstInFlightPrerequisite(Enrollment enrollment,
                                             PlanDefinitionParser.StepMetadata dependentStepMetadata,
                                             StepInstance completedStepInstance) {
        List<String> prerequisiteIds =
                enrollment.protocol().dependencyGraph().predecessorsOf(dependentStepMetadata.id());
        for (String prerequisiteId : prerequisiteIds) {
            if (prerequisiteId.equals(completedStepInstance.getActionId())) {
                continue; // the prerequisite that has just completed
            }
            if (stepInstanceScope.hasOutstandingStepInstance(enrollment, prerequisiteId,
                    completedStepInstance.getStepGroupInstance())) {
                return prerequisiteId;
            }
        }
        return null;
    }
}
