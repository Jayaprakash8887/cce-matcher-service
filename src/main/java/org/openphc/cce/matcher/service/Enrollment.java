package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An enrollment as the work for one event sees it: the protocol instance, its parsed protocol, and its
 * step instances, read once under the enrollment's lock (see {@link EnrollmentLoader#lockAndLoad}).
 *
 * <p>The step instances are kept by step, so a lookup reads only that step's instances, not the whole
 * enrollment.
 *
 * <p>The step instances can change: work that creates step instances adds them here, so later work in the
 * same transaction sees them. Progressive instantiation relies on that for a step waiting on two
 * prerequisites. Backfill relies on it not to create a step that progressive instantiation has just
 * created.
 */
final class Enrollment {

    private final ProtocolInstance protocolInstance;
    private final ParsedProtocolCache.ParsedProtocol protocol;
    private final Map<String, List<StepInstance>> stepInstancesByStepId = new LinkedHashMap<>();

    Enrollment(ProtocolInstance protocolInstance, ParsedProtocolCache.ParsedProtocol protocol,
               Collection<StepInstance> stepInstances) {
        this.protocolInstance = protocolInstance;
        this.protocol = protocol;
        add(stepInstances);
    }

    ProtocolInstance protocolInstance() {
        return protocolInstance;
    }

    ParsedProtocolCache.ParsedProtocol protocol() {
        return protocol;
    }

    /** The step the protocol declares as {@code stepActionId}, or null when it declares none. */
    PlanDefinitionParser.StepMetadata step(String stepActionId) {
        return protocol.step(stepActionId);
    }

    /** {@code stepActionId}'s step instances, in the order they were read or created. */
    List<StepInstance> stepInstancesOf(String stepActionId) {
        return Collections.unmodifiableList(stepInstancesByStepId.getOrDefault(stepActionId, List.of()));
    }

    /** The steps that have at least one step instance. */
    Set<String> stepIdsWithStepInstances() {
        return Collections.unmodifiableSet(stepInstancesByStepId.keySet());
    }

    /**
     * {@code groupId} and the groups around it, found by following each group's parent: the group itself
     * first, the top-level group last.
     */
    List<PlanDefinitionParser.GroupMetadata> groupsFromInnermostOut(String groupId) {
        List<PlanDefinitionParser.GroupMetadata> groups = new ArrayList<>();
        for (String enclosingGroupId = groupId; enclosingGroupId != null;
             enclosingGroupId = protocol.group(enclosingGroupId).parentGroupId()) {
            groups.add(protocol.group(enclosingGroupId));
        }
        return groups;
    }

    void add(StepInstance createdStepInstance) {
        stepInstancesByStepId.computeIfAbsent(createdStepInstance.getActionId(), stepId -> new ArrayList<>())
                .add(createdStepInstance);
    }

    void add(Collection<StepInstance> createdStepInstances) {
        createdStepInstances.forEach(this::add);
    }
}
