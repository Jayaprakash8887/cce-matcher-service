package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * An enrollment as the work for one event sees it: the protocol instance, its parsed protocol, and its
 * step instances, read once under the enrollment's lock ({@link ProtocolInstanceService#lockForStepGroups}).
 *
 * <p>{@code stepInstances} is mutable by design: work that creates step instances adds them here, so later
 * work in the same transaction sees them. Progressive instantiation relies on that for a step waiting on
 * two prerequisites, and backfill relies on it not to create a step that progressive instantiation has
 * just created.
 */
record Enrollment(ProtocolInstance protocolInstance,
                  ParsedProtocolCache.ParsedProtocol protocol,
                  List<StepInstance> stepInstances) {

    /** The step the protocol declares as {@code stepActionId}, or null when it declares none. */
    PlanDefinitionParser.StepMetadata step(String stepActionId) {
        return protocol.step(stepActionId);
    }

    /** {@code groupId}'s enclosing groups and the group itself, from the top-level group down. */
    List<PlanDefinitionParser.GroupMetadata> groupsFromTopDown(String groupId) {
        LinkedList<PlanDefinitionParser.GroupMetadata> groups = new LinkedList<>();
        for (String enclosingGroupId = groupId; enclosingGroupId != null;
             enclosingGroupId = protocol.group(enclosingGroupId).parentGroupId()) {
            groups.addFirst(protocol.group(enclosingGroupId));
        }
        return groups;
    }

    void add(StepInstance createdStepInstance) {
        stepInstances.add(createdStepInstance);
    }

    void add(Collection<StepInstance> createdStepInstances) {
        stepInstances.addAll(createdStepInstances);
    }
}
