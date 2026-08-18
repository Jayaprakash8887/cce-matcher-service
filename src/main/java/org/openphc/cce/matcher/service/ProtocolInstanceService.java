package org.openphc.cce.matcher.service;

import org.openphc.cce.common.service.AuditService;
import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ProtocolInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolInstanceService.class);

    private final ProtocolInstanceRepository protocolInstanceRepository;
    private final AuditService auditService;
    private final StateTransitionHistoryService stateTransitionHistoryService;

    public ProtocolInstanceService(ProtocolInstanceRepository protocolInstanceRepository,
                                   AuditService auditService,
                                   StateTransitionHistoryService stateTransitionHistoryService) {
        this.protocolInstanceRepository = protocolInstanceRepository;
        this.auditService = auditService;
        this.stateTransitionHistoryService = stateTransitionHistoryService;
    }

    /**
     * Enroll a patient in a protocol. If the patient already has an ACTIVE instance
     * for the same protocol definition, the existing instance is returned (skip re-enrollment).
     */
    public ProtocolInstance enrollPatient(String patientId, ProtocolDefinition protocolDef,
                                         OffsetDateTime enrolledAt) {
        // Check for existing ACTIVE enrollment
        Optional<ProtocolInstance> existing = protocolInstanceRepository
                .findByPatientIdAndProtocolDefinitionIdAndStatus(
                        patientId, protocolDef.getId(), ProtocolInstanceStatus.ACTIVE);

        if (existing.isPresent()) {
            log.info("Patient {} already enrolled in protocol {} — skipping",
                    patientId, protocolDef.getCanonical());
            return existing.get();
        }

        ProtocolInstance instance = ProtocolInstance.builder()
                .patientId(patientId)
                .protocolDefinition(protocolDef)
                .protocolCanonical(protocolDef.getCanonical())
                .enrolledAt(enrolledAt)
                .status(ProtocolInstanceStatus.ACTIVE)
                .build();

        instance = protocolInstanceRepository.save(instance);

        // Capture the initial ACTIVE status in append-only history.
        stateTransitionHistoryService.recordProtocolInstanceTransition(instance, instance.getEnrolledAt());

        auditService.audit("MATCHER", "PROTOCOL_ENROLLED", "system",
                "ProtocolInstance", instance.getId().toString(),
                Map.of("patientId", patientId,
                        "protocolCanonical", protocolDef.getCanonical(),
                        "protocolDefinitionId", protocolDef.getId().toString()));

        log.info("Enrolled patient {} in protocol {} (instanceId={})",
                patientId, protocolDef.getCanonical(), instance.getId());

        return instance;
    }

    @Transactional(readOnly = true)
    public ProtocolInstance findById(UUID id) {
        return findByIdOrThrow(id);
    }

    private ProtocolInstance findByIdOrThrow(UUID id) {
        return protocolInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Protocol instance not found: " + id));
    }
}
