package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.repository.StepGroupInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Objects;
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
    private final ZoneId calendarZone;

    /**
     * @param calendarZone the deployment's time zone, in which a {@code calendar-day} group's days are
     *                     counted. One per deployment: clinical times are stored in UTC, and a check-in at
     *                     23:30 UTC is already the next day in Kigali.
     */
    public StepGroupService(StepGroupInstanceRepository groupInstanceRepository,
                            ProtocolInstanceRepository protocolInstanceRepository,
                            @Value("${cce.step-groups.calendar-zone:UTC}") ZoneId calendarZone) {
        this.groupInstanceRepository = groupInstanceRepository;
        this.protocolInstanceRepository = protocolInstanceRepository;
        this.calendarZone = calendarZone;
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

    /**
     * A group's latest cycle within one occurrence of its parent, or within the enrollment for a
     * top-level group: the one with the highest {@code cycle_index}. Empty when none has been opened.
     */
    public Optional<StepGroupInstance> latestCycle(ProtocolInstance protocolInstance, String groupActionId,
                                                   StepGroupInstance parent) {
        return groupInstanceRepository.findByProtocolInstanceIdAndGroupActionId(protocolInstance.getId(), groupActionId)
                .stream()
                .filter(occurrence -> parent == null
                        ? occurrence.getParentGroupInstance() == null
                        : occurrence.getParentGroupInstance() != null
                                && Objects.equals(occurrence.getParentGroupInstance().getId(), parent.getId()))
                .max(Comparator.comparingInt(StepGroupInstance::getCycleIndex));
    }

    /**
     * Whether {@code at} falls on a later calendar day, in the deployment's time zone, than the day
     * {@code cycle} opened. A cycle's day is not stored: the first event of its day opened it.
     */
    public boolean isLaterDay(StepGroupInstance cycle, OffsetDateTime at) {
        return at.atZoneSameInstant(calendarZone).toLocalDate()
                .isAfter(cycle.getOpenedAt().atZoneSameInstant(calendarZone).toLocalDate());
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
