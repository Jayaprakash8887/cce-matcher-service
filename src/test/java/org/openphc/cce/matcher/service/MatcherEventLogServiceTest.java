package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.matcher.domain.entity.MatcherEventLog;
import org.openphc.cce.common.enums.ProcessingStatus;
import org.openphc.cce.matcher.domain.repository.MatcherEventLogRepository;
import org.openphc.cce.common.event.CloudEventMessage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatcherEventLogServiceTest {

    @Mock
    private MatcherEventLogRepository eventLogRepository;

    private MatcherEventLogService eventLogService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        eventLogService = new MatcherEventLogService(eventLogRepository);
    }

    @Test
    void isDuplicate_existingEvent_returnsTrue() {
        when(eventLogRepository.existsByCloudeventsIdAndSource("evt-001", "ebuzima"))
                .thenReturn(true);

        assertTrue(eventLogService.isDuplicate("evt-001", "ebuzima"));
        verify(eventLogRepository).existsByCloudeventsIdAndSource("evt-001", "ebuzima");
    }

    @Test
    void isDuplicate_newEvent_returnsFalse() {
        when(eventLogRepository.existsByCloudeventsIdAndSource("evt-new", "rhie"))
                .thenReturn(false);

        assertFalse(eventLogService.isDuplicate("evt-new", "rhie"));
    }

    @Test
    void recordEvent_mapsAllFieldsCorrectly() {
        CloudEventMessage message = CloudEventMessage.builder()
                .id("evt-002")
                .source("ebuzima")
                .type("Observation")
                .specversion("1.0")
                .subject("patient-123")
                .time(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC))
                .datacontenttype("application/fhir+json")
                .correlationid("corr-abc-123")
                .sourceeventid("lab-evt-789")
                .facilityid("0002")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Observation", "status", "final")))
                .build();

        when(eventLogRepository.save(any(MatcherEventLog.class))).thenAnswer(inv -> inv.getArgument(0));

        eventLogService.recordEvent(message, ProcessingStatus.ZERO_MATCH);

        ArgumentCaptor<MatcherEventLog> captor = ArgumentCaptor.forClass(MatcherEventLog.class);
        verify(eventLogRepository).save(captor.capture());

        MatcherEventLog saved = captor.getValue();
        assertEquals("evt-002", saved.getCloudeventsId());
        assertEquals("ebuzima", saved.getSource());
        assertEquals("corr-abc-123", saved.getCorrelationId());
        assertNotNull(saved.getReceivedAt());
        assertEquals(ProcessingStatus.ZERO_MATCH, saved.getProcessingStatus());

        // Verify data is converted to JsonNode
        JsonNode data = saved.getData();
        assertNotNull(data);
        assertEquals("Observation", data.get("resourceType").asText());
        assertEquals("final", data.get("status").asText());
    }

    @Test
    void recordEvent_setsReceivedAtToCurrentUtcTime() {
        CloudEventMessage message = CloudEventMessage.builder()
                .id("evt-003")
                .source("test")
                .type("Encounter")
                .subject("patient-456")
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationid("corr-test")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Encounter")))
                .build();

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        when(eventLogRepository.save(any(MatcherEventLog.class))).thenAnswer(inv -> inv.getArgument(0));
        eventLogService.recordEvent(message, ProcessingStatus.MATCHED);

        ArgumentCaptor<MatcherEventLog> captor = ArgumentCaptor.forClass(MatcherEventLog.class);
        verify(eventLogRepository).save(captor.capture());

        OffsetDateTime receivedAt = captor.getValue().getReceivedAt();
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        assertFalse(receivedAt.isBefore(before));
        assertFalse(receivedAt.isAfter(after));
    }

    @Test
    void recordEvent_nullableFieldsHandled() {
        CloudEventMessage message = CloudEventMessage.builder()
                .id("evt-004")
                .source("src")
                .type("Observation")
                .subject("patient-789")
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .data(objectMapper.valueToTree(Map.of("resourceType", "Observation")))
                .build();
        // correlationid is null

        when(eventLogRepository.save(any(MatcherEventLog.class))).thenAnswer(inv -> inv.getArgument(0));

        eventLogService.recordEvent(message, ProcessingStatus.ZERO_MATCH);

        ArgumentCaptor<MatcherEventLog> captor = ArgumentCaptor.forClass(MatcherEventLog.class);
        verify(eventLogRepository).save(captor.capture());

        assertNull(captor.getValue().getCorrelationId());
    }

    @Test
    void updateStatus_updatesAndSaves() {
        MatcherEventLog eventLog = MatcherEventLog.builder()
                .processingStatus(ProcessingStatus.ZERO_MATCH)
                .build();

        when(eventLogRepository.save(any(MatcherEventLog.class))).thenAnswer(inv -> inv.getArgument(0));

        eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);

        assertEquals(ProcessingStatus.MATCHED, eventLog.getProcessingStatus());
        verify(eventLogRepository).save(eventLog);
    }
}
