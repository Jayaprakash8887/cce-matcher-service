package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.repository.StepGroupInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which of a step's instances count for work in a group instance. A step that repeats with its group has
 * instances in many group instances, and one cycle never sees another's. The order-violation check, the
 * wait for other prerequisites, backfill and a group's planned due date all read a step's instances
 * through here, so they agree on which count.
 */
@Service
@Transactional
public class StepInstanceScope {

    private final StepGroupInstanceRepository groupInstanceRepository;

    public StepInstanceScope(StepGroupInstanceRepository groupInstanceRepository) {
        this.groupInstanceRepository = groupInstanceRepository;
    }

    /**
     * {@code stepActionId}'s step instances that count for work in {@code fromGroupInstance}, such as a
     * predecessor's when a step completes there:
     * <ul>
     *   <li>a step that shares a group with that work: its step instances in the shared group instance, so
     *       one cycle never sees another's;</li>
     *   <li>a step in a group that shares none: its step instances in its group's latest cycle, inside its
     *       parent's latest, where new work goes. A top-level treatment after a visit's prescription sees
     *       this visit's prescription, not an earlier visit's;</li>
     *   <li>a step outside every group: all its step instances in the enrollment, since it happens once per
     *       enrollment.</li>
     * </ul>
     */
    public List<StepInstance> stepInstancesInScope(Enrollment enrollment, String stepActionId,
                                                   StepGroupInstance fromGroupInstance) {
        StepGroupInstance sharedGroupInstance =
                innermostSharedGroupInstance(enrollment, fromGroupInstance, stepActionId);
        PlanDefinitionParser.StepMetadata stepMetadata = enrollment.step(stepActionId);
        StepGroupInstance scope;
        if (sharedGroupInstance != null) {
            scope = sharedGroupInstance; // shares a group with the work: that group instance only
        } else if (stepMetadata == null || stepMetadata.groupId() == null) {
            scope = null; // outside every group: the whole enrollment
        } else {
            scope = latestGroupInstancePath(enrollment, stepMetadata.groupId()); // its group's latest cycle
        }
        return enrollment.stepInstances().stream()
                .filter(stepInstance -> stepActionId.equals(stepInstance.getActionId()))
                .filter(stepInstance -> scope == null || isInside(stepInstance.getStepGroupInstance(), scope))
                .toList();
    }

    /**
     * Whether {@code stepActionId} has a step instance that counts for work in {@code fromGroupInstance}
     * and is still outstanding (see {@link #isOutstanding}).
     */
    public boolean hasOutstandingStepInstance(Enrollment enrollment, String stepActionId,
                                              StepGroupInstance fromGroupInstance) {
        return stepInstancesInScope(enrollment, stepActionId, fromGroupInstance).stream()
                .anyMatch(StepInstanceScope::isOutstanding);
    }

    /**
     * Whether a step instance is still genuinely outstanding: its event has not arrived and its SLA can
     * still move. Excludes SLAs already settled — {@code MISSED}, written off, and {@code MET},
     * recorded on time. {@code OVERDUE} is not settled: a late event can still complete such a step.
     */
    static boolean isOutstanding(StepInstance stepInstance) {
        return stepInstance.getStepStatus() == StepStatus.NOT_STARTED
                && isLiveSlaStatus(stepInstance.getSlaStatus());
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
     * Whether {@code stepActionId} already has a step instance where work would create one: in
     * {@code groupInstance} itself, or anywhere in the enrollment for a step outside every group
     * ({@code groupInstance} null).
     */
    static boolean hasStepInstance(Enrollment enrollment, String stepActionId, StepGroupInstance groupInstance) {
        return enrollment.stepInstances().stream()
                .filter(stepInstance -> stepActionId.equals(stepInstance.getActionId()))
                .anyMatch(stepInstance -> groupInstance == null || isDirectlyIn(stepInstance, groupInstance));
    }

    /** Whether {@code stepInstance} is attached to {@code groupInstance} itself, not to one nested in it. */
    static boolean isDirectlyIn(StepInstance stepInstance, StepGroupInstance groupInstance) {
        return stepInstance.getStepGroupInstance() != null
                && Objects.equals(stepInstance.getStepGroupInstance().getId(), groupInstance.getId());
    }

    /**
     * The deepest group instance on the way down to {@code groupId}'s latest cycle, taking the latest cycle
     * at each level from the top-level group: the group's own latest cycle, or the instance where it would
     * open when it hasn't yet, which holds none of its step instances. Null when not even the top-level
     * group is open, in which case none of its steps has a step instance.
     */
    private StepGroupInstance latestGroupInstancePath(Enrollment enrollment, String groupId) {
        StepGroupInstance deepestGroupInstance = null;
        for (PlanDefinitionParser.GroupMetadata groupMetadata : enrollment.groupsFromTopDown(groupId)) {
            Optional<StepGroupInstance> latestGroupInstance = groupInstanceRepository.findLatestCycle(
                    enrollment.protocolInstance().getId(), groupMetadata.id(),
                    deepestGroupInstance == null ? null : deepestGroupInstance.getId());
            if (latestGroupInstance.isEmpty()) {
                break; // this level hasn't opened yet, so nothing below it has either
            }
            deepestGroupInstance = latestGroupInstance.get();
        }
        return deepestGroupInstance;
    }

    /**
     * {@code fromGroupInstance}'s instance of the innermost group that also encloses {@code stepActionId},
     * or null.
     */
    private static StepGroupInstance innermostSharedGroupInstance(Enrollment enrollment,
                                                                  StepGroupInstance fromGroupInstance,
                                                                  String stepActionId) {
        PlanDefinitionParser.StepMetadata stepMetadata = enrollment.step(stepActionId);
        if (fromGroupInstance == null || stepMetadata == null || stepMetadata.groupId() == null) {
            return null; // the work or the step is outside every group: nothing is shared
        }
        Set<String> enclosingGroupIds = new HashSet<>();
        for (String groupId = stepMetadata.groupId(); groupId != null; ) {
            enclosingGroupIds.add(groupId);
            PlanDefinitionParser.GroupMetadata groupMetadata = enrollment.protocol().group(groupId);
            groupId = groupMetadata != null ? groupMetadata.parentGroupId() : null;
        }
        for (StepGroupInstance groupInstance = fromGroupInstance; groupInstance != null;
             groupInstance = groupInstance.getParentGroupInstance()) {
            if (enclosingGroupIds.contains(groupInstance.getGroupActionId())) {
                return groupInstance;
            }
        }
        return null;
    }

    /** Whether {@code groupInstance} is {@code scope} or nested somewhere inside it. */
    private static boolean isInside(StepGroupInstance groupInstance, StepGroupInstance scope) {
        for (StepGroupInstance enclosing = groupInstance; enclosing != null;
             enclosing = enclosing.getParentGroupInstance()) {
            if (Objects.equals(enclosing.getId(), scope.getId())) {
                return true;
            }
        }
        return false;
    }
}
