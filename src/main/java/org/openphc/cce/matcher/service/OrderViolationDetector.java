package org.openphc.cce.matcher.service;

import org.openphc.cce.common.deviation.DeviationRecorder;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Order violations, one of the checks that follow a step instance's completion: a step completed while
 * one of its mandatory predecessors is still outstanding is recorded as an {@code ORDER_VIOLATION}
 * deviation.
 */
@Service
@Transactional
public class OrderViolationDetector {

    private static final Logger log = LoggerFactory.getLogger(OrderViolationDetector.class);

    private final StepInstanceScope stepInstanceScope;
    private final DeviationRecorder deviationRecorder;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;

    public OrderViolationDetector(StepInstanceScope stepInstanceScope,
                                  DeviationRecorder deviationRecorder,
                                  IntelligenceActionEvaluator intelligenceActionEvaluator) {
        this.stepInstanceScope = stepInstanceScope;
        this.deviationRecorder = deviationRecorder;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
    }

    /**
     * Detect order violations: when a step completes, check whether any of its mandatory
     * ({@code requiredBehavior="must"}) immediate predecessors is still outstanding (see
     * {@link StepInstanceScope#isOutstanding}), where the step completed. The immediate predecessors of
     * step X are the prerequisites the normalized dependency graph records for it (see
     * {@link PlanDefinitionParser#buildDependencyGraph}).
     */
    public void detectOrderViolations(Enrollment enrollment, StepInstance completedStepInstance) {
        String completedStepId = completedStepInstance.getActionId();
        Set<String> mustStepIds = PlanDefinitionParser.mustStepIds(enrollment.protocol().steps());

        // Its mandatory immediate predecessors that are still outstanding where it completed.
        List<String> incompletePrerequisites = enrollment.protocol().dependencyGraph()
                .predecessorsOf(completedStepId).stream()
                .distinct()
                .filter(mustStepIds::contains)
                .filter(predecessorId -> stepInstanceScope.hasOutstandingStepInstance(enrollment, predecessorId,
                        completedStepInstance.getStepGroupInstance()))
                .toList();
        if (incompletePrerequisites.isEmpty()) {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("incompletePrerequisites", incompletePrerequisites);
        // Key kept as-is: it is persisted in deviation.metadata and read downstream.
        metadata.put("completedActionId", completedStepId);

        // Once per step instance: completing one that is not NOT_STARTED is refused, so this cannot run
        // twice for one.
        Deviation deviation = deviationRecorder.recordDeviation(completedStepInstance,
                DeviationType.ORDER_VIOLATION, metadata);

        log.warn("Order violation detected: step {} (actionId={}) completed while "
                        + "prerequisite steps {} are still incomplete",
                completedStepInstance.getId(), completedStepId, incompletePrerequisites);

        intelligenceActionEvaluator.evaluateOnDeviation(completedStepInstance, deviation);
    }
}
