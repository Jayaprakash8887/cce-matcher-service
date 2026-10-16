package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.repository.StepGroupInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The rows of {@code step_group_instance}: finding an occurrence and opening one.
 *
 * <p>Creating the steps an occurrence holds is {@link StepInstanceService}'s job, which calls this
 * service rather than the other way round. Every method here expects the caller to hold the
 * enrollment's lock ({@link #lock}).
 */
@Service
@Transactional
public class StepGroupService {

    private static final Logger log = LoggerFactory.getLogger(StepGroupService.class);

    private final StepGroupInstanceRepository groupInstanceRepository;
    private final ProtocolInstanceRepository protocolInstanceRepository;

    public StepGroupService(StepGroupInstanceRepository groupInstanceRepository,
                            ProtocolInstanceRepository protocolInstanceRepository) {
        this.groupInstanceRepository = groupInstanceRepository;
        this.protocolInstanceRepository = protocolInstanceRepository;
    }

    /**
     * Lock the enrollment's row until the transaction ends, when its protocol has step groups.
     *
     * <p>Two events for one patient can be processed at once. Without the lock, each could open the same
     * occurrence, or create the same step in it twice.
     * A protocol without groups pays nothing.
     */
    public void lock(ProtocolInstance protocolInstance, ParsedProtocolCache.ParsedProtocol protocol) {
        if (protocol.hasGroups()) {
            protocolInstanceRepository.findByIdForUpdate(protocolInstance.getId());
        }
    }

    /** A group's occurrence for one cycle within one occurrence of its parent, if it has been opened. */
    public Optional<StepGroupInstance> find(ProtocolInstance protocolInstance, String groupActionId,
                                            StepGroupInstance parent, int cycleIndex) {
        return parent == null
                ? groupInstanceRepository.findByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIsNullAndCycleIndex(
                        protocolInstance.getId(), groupActionId, cycleIndex)
                : groupInstanceRepository.findByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIdAndCycleIndex(
                        protocolInstance.getId(), groupActionId, parent.getId(), cycleIndex);
    }

    /** Insert an occurrence; the caller holds the enrollment's lock and has found none, and the unique key is the backstop. */
    public StepGroupInstance open(ProtocolInstance protocolInstance, String groupActionId,
                                  StepGroupInstance parent, int cycleIndex,
                                  OffsetDateTime dueDate, OffsetDateTime openedAt) {
        StepGroupInstance occurrence = groupInstanceRepository.save(StepGroupInstance.builder()
                .protocolInstance(protocolInstance)
                .parentGroupInstance(parent)
                .groupActionId(groupActionId)
                .cycleIndex(cycleIndex)
                .dueDate(dueDate)
                .openedAt(openedAt)
                .build());

        log.info("Opened step group occurrence: group={}, cycle={}, instanceId={}, occurrenceId={}, due={}",
                groupActionId, cycleIndex, protocolInstance.getId(), occurrence.getId(), dueDate);
        return occurrence;
    }
}
