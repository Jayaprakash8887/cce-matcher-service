package org.openphc.cce.matcher.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.matcher.domain.entity.ProtocolInstanceHistory;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.matcher.domain.entity.StepInstanceHistory;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.matcher.domain.repository.ProtocolInstanceHistoryRepository;
import org.openphc.cce.matcher.domain.repository.StepInstanceHistoryRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link StateTransitionHistoryService}, which appends immutable history rows for
 * protocol- and step-instance transitions. Verifies the captured history row mirrors the current
 * instance state, including the nullable step completion status.
 */
@ExtendWith(MockitoExtension.class)
class StateTransitionHistoryServiceTest {

    @Mock
    private ProtocolInstanceHistoryRepository protocolInstanceHistoryRepository;

    @Mock
    private StepInstanceHistoryRepository stepInstanceHistoryRepository;

    private StateTransitionHistoryService service;

    private final OffsetDateTime changedAt = OffsetDateTime.of(2026, 6, 30, 12, 0, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new StateTransitionHistoryService(
                protocolInstanceHistoryRepository, stepInstanceHistoryRepository);
    }

    @Test
    void recordProtocolInstanceTransition_savesHistoryMirroringInstance() {
        UUID protocolInstanceId = UUID.randomUUID();
        ProtocolInstance instance = ProtocolInstance.builder()
                .id(protocolInstanceId)
                .status(ProtocolInstanceStatus.ACTIVE)
                .build();

        service.recordProtocolInstanceTransition(instance, changedAt);

        ArgumentCaptor<ProtocolInstanceHistory> captor =
                ArgumentCaptor.forClass(ProtocolInstanceHistory.class);
        verify(protocolInstanceHistoryRepository).save(captor.capture());
        ProtocolInstanceHistory saved = captor.getValue();
        assertEquals(protocolInstanceId, saved.getProtocolInstanceId());
        assertEquals("ACTIVE", saved.getStatus());
        assertEquals(changedAt, saved.getChangedAt());
    }

    @Test
    void recordStepInstanceTransition_completedOnTime_savesBothStatuses() {
        UUID stepInstanceId = UUID.randomUUID();
        StepInstance step = StepInstance.builder()
                .id(stepInstanceId)
                .stepStatus(StepStatus.COMPLETED)
                .slaStatus(SlaStatus.MET)
                .build();

        service.recordStepInstanceTransition(step, changedAt);

        ArgumentCaptor<StepInstanceHistory> captor = ArgumentCaptor.forClass(StepInstanceHistory.class);
        verify(stepInstanceHistoryRepository).save(captor.capture());
        StepInstanceHistory saved = captor.getValue();
        assertEquals(stepInstanceId, saved.getStepInstanceId());
        assertEquals("COMPLETED", saved.getStepStatus());
        assertEquals("MET", saved.getSlaStatus());
        assertEquals(changedAt, saved.getChangedAt());
    }

    @Test
    void recordStepInstanceTransition_completedLate_recordsTheBreachAlongsideTheCompletion() {
        UUID stepInstanceId = UUID.randomUUID();
        // The pairing the old single-column model could not express.
        StepInstance step = StepInstance.builder()
                .id(stepInstanceId)
                .stepStatus(StepStatus.COMPLETED)
                .slaStatus(SlaStatus.MISSED)
                .build();

        service.recordStepInstanceTransition(step, changedAt);

        ArgumentCaptor<StepInstanceHistory> captor = ArgumentCaptor.forClass(StepInstanceHistory.class);
        verify(stepInstanceHistoryRepository).save(captor.capture());
        StepInstanceHistory saved = captor.getValue();
        assertEquals("COMPLETED", saved.getStepStatus());
        assertEquals("MISSED", saved.getSlaStatus());
    }

    @Test
    void recordStepInstanceTransition_notStartedAndOverdue_savesBothStatuses() {
        StepInstance step = StepInstance.builder()
                .id(UUID.randomUUID())
                .stepStatus(StepStatus.NOT_STARTED)
                .slaStatus(SlaStatus.OVERDUE)
                .build();

        service.recordStepInstanceTransition(step, changedAt);

        ArgumentCaptor<StepInstanceHistory> captor = ArgumentCaptor.forClass(StepInstanceHistory.class);
        verify(stepInstanceHistoryRepository).save(captor.capture());
        StepInstanceHistory saved = captor.getValue();
        assertEquals("NOT_STARTED", saved.getStepStatus());
        assertEquals("OVERDUE", saved.getSlaStatus());
    }
}
