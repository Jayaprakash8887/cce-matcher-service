package org.openphc.cce.matcher.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.event.CloudEventMessage;
import org.openphc.cce.matcher.service.MatcherEngine;
import org.openphc.cce.matcher.service.FacilityService;
import org.slf4j.MDC;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboundEventConsumerTest {

    @Mock private MatcherEngine matcherEngine;
    @Mock private FacilityService facilityService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InboundEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new InboundEventConsumer(matcherEngine, facilityService, new SimpleMeterRegistry());
        MDC.clear();
    }

    @Nested
    class SuccessfulProcessing {

        @Test
        void successfulProcessing_delegatesToEngine() {
            CloudEventMessage event = buildEvent();

            consumer.consume(event);

            verify(matcherEngine).processInboundEvent(event);
        }

        @Test
        void mdcClearedAfterSuccess() {
            CloudEventMessage event = buildEvent();

            consumer.consume(event);

            assertNull(MDC.get("correlationId"), "MDC should be cleared after processing");
            assertNull(MDC.get("source"));
            assertNull(MDC.get("eventType"));
            assertNull(MDC.get("subject"));
        }
    }

    @Nested
    class FailedProcessing {

        @Test
        void processingException_propagatesToErrorHandler() {
            CloudEventMessage event = buildEvent();
            doThrow(new RuntimeException("Processing failed"))
                    .when(matcherEngine).processInboundEvent(event);

            assertThrows(RuntimeException.class, () -> consumer.consume(event));

            verify(matcherEngine).processInboundEvent(event);
        }

        @Test
        void mdcClearedAfterFailure() {
            CloudEventMessage event = buildEvent();
            doThrow(new RuntimeException("Processing failed"))
                    .when(matcherEngine).processInboundEvent(event);

            assertThrows(RuntimeException.class, () -> consumer.consume(event));

            assertNull(MDC.get("correlationId"), "MDC should be cleared even after failure");
        }

        @Test
        void facilityRegistrationFailure_doesNotBlockMatcherProcessing() {
            CloudEventMessage event = buildEvent();
            doThrow(new RuntimeException("DB unavailable"))
                    .when(facilityService).upsertFacility(event);

            // Should NOT throw — facility failure is swallowed
            assertDoesNotThrow(() -> consumer.consume(event));

            // Matcher engine must still be called
            verify(matcherEngine).processInboundEvent(event);
        }
    }

    @Nested
    class MdcPopulation {

        @Test
        void mdcPopulatedDuringProcessing() {
            CloudEventMessage event = buildEvent();
            doAnswer(invocation -> {
                assertEquals(event.getCorrelationid(), MDC.get("correlationId"));
                assertEquals(event.getSource(), MDC.get("source"));
                assertEquals(event.getType(), MDC.get("eventType"));
                assertEquals(event.getSubject(), MDC.get("subject"));
                return null;
            }).when(matcherEngine).processInboundEvent(event);

            consumer.consume(event);

            verify(matcherEngine).processInboundEvent(event);
        }
    }

    private CloudEventMessage buildEvent() {
        return CloudEventMessage.builder()
                .id("ce-" + UUID.randomUUID())
                .source("http://test-facility.openphc.org/ehr")
                .type("org.openphc.clinical.observation.created")
                .specversion("1.0")
                .subject("patient-1")
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Observation")))
                .build();
    }
}
