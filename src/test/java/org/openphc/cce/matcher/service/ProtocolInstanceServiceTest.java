package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.service.StateTransitionHistoryService;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProtocolInstanceServiceTest {

    @Mock
    private ProtocolInstanceRepository protocolInstanceRepository;


    @Mock
    private StateTransitionHistoryService stateTransitionHistoryService;

    private ProtocolInstanceService service;

    @BeforeEach
    void setUp() {
        service = new ProtocolInstanceService(protocolInstanceRepository,
                stateTransitionHistoryService);
    }

    @Nested
    class EnrollPatient {

        @Test
        void newPatient_createsActiveInstance() {
            ProtocolDefinition protocolDef = buildProtocolDefinition();
            OffsetDateTime enrolledAt = OffsetDateTime.now(ZoneOffset.UTC);

            when(protocolInstanceRepository.findByPatientIdAndProtocolDefinitionIdAndStatus(
                    "patient-1", protocolDef.getId(), ProtocolInstanceStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(protocolInstanceRepository.save(any(ProtocolInstance.class))).thenAnswer(invocation -> {
                ProtocolInstance pi = invocation.getArgument(0);
                pi.setId(UUID.randomUUID());
                return pi;
            });

            ProtocolInstance result = service.enrollPatient("patient-1", protocolDef, enrolledAt);

            assertNotNull(result);
            assertNotNull(result.getId());
            assertEquals("patient-1", result.getPatientId());
            assertEquals(ProtocolInstanceStatus.ACTIVE, result.getStatus());
            // The canonical is not stored on the enrolment; it is the definition's, reached by FK.
            assertEquals(protocolDef.getCanonical(),
                    result.getProtocolDefinition().getCanonical());
            assertEquals(enrolledAt, result.getEnrolledAt());

            verify(protocolInstanceRepository).save(any(ProtocolInstance.class));
            // The initial ACTIVE status is recorded in append-only history at the enrollment time.
            verify(stateTransitionHistoryService).recordProtocolInstanceTransition(result, enrolledAt);
        }

        @Test
        void existingActiveEnrollment_skipsReEnrollment() {
            ProtocolDefinition protocolDef = buildProtocolDefinition();
            ProtocolInstance existing = ProtocolInstance.builder()
                    .id(UUID.randomUUID())
                    .patientId("patient-1")
                    .protocolDefinition(protocolDef)
                    .status(ProtocolInstanceStatus.ACTIVE)
                    .build();

            when(protocolInstanceRepository.findByPatientIdAndProtocolDefinitionIdAndStatus(
                    "patient-1", protocolDef.getId(), ProtocolInstanceStatus.ACTIVE))
                    .thenReturn(Optional.of(existing));

            ProtocolInstance result = service.enrollPatient("patient-1", protocolDef,
                    OffsetDateTime.now(ZoneOffset.UTC));

            assertSame(existing, result);
            verify(protocolInstanceRepository, never()).save(any());
            verify(stateTransitionHistoryService, never()).recordProtocolInstanceTransition(any(), any());
        }
    }

    @Nested
    class ReadOperations {

        @Test
        void findById_existing_returnsInstance() {
            UUID instanceId = UUID.randomUUID();
            ProtocolInstance instance = buildProtocolInstance(instanceId, ProtocolInstanceStatus.ACTIVE);
            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

            ProtocolInstance result = service.findById(instanceId);
            assertEquals(instanceId, result.getId());
        }

        @Test
        void findById_notFound_throwsEntityNotFound() {
            UUID instanceId = UUID.randomUUID();
            when(protocolInstanceRepository.findById(instanceId)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> service.findById(instanceId));
        }
    }

    // ── Helpers ──

    private ProtocolDefinition buildProtocolDefinition() {
        return ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(JsonNodeFactory.instance.objectNode())
                .build();
    }

    private ProtocolInstance buildProtocolInstance(UUID id, ProtocolInstanceStatus status) {
        return ProtocolInstance.builder()
                .id(id)
                .patientId("patient-1")
                .protocolDefinition(buildProtocolDefinition())
                .status(status)
                .build();
    }
}
