package org.openphc.cce.matcher.observability;

import org.openphc.cce.common.service.SlaThresholdReader;
import org.openphc.cce.common.service.ActionDefinitionResolver;
import org.openphc.cce.common.service.IntelligenceActionEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.kafka.KafkaTopicProperties;
import org.openphc.cce.matcher.config.ObservabilityConfig;
import org.openphc.cce.common.enums.ActionDefinitionStatus;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.repository.ActionDefinitionRepository;
import org.openphc.cce.common.repository.DeviationRepository;
import org.openphc.cce.common.repository.IntelligenceEventLogRepository;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.fhir.ExpressionEvaluationService;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.matcher.kafka.consumer.InboundEventConsumer;
import org.openphc.cce.common.kafka.IntelligenceTriggerProducer;
import org.openphc.cce.matcher.service.*;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies every custom Micrometer metric is registered under the expected name and tags. These are
 * exposed via {@code /actuator/prometheus}, so a rename here is a downstream dashboard break.
 *
 * <p>Each meter is asserted against the component that actually registers it, by constructing that
 * component with a fresh registry. Asserting against a separate declaration list would only prove
 * the list matches itself.
 */
class ActuatorMetricsTest {

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();

        ProtocolInstanceRepository protocolRepo = mock(ProtocolInstanceRepository.class);
        when(protocolRepo.countByStatus(ProtocolInstanceStatus.ACTIVE)).thenReturn(3L);
        ActionDefinitionRepository actionDefRepo = mock(ActionDefinitionRepository.class);
        when(actionDefRepo.countByStatus(ActionDefinitionStatus.ACTIVE)).thenReturn(2L);

        MeterBinder binder = new ObservabilityConfig().cceMetrics(protocolRepo, actionDefRepo);
        binder.bindTo(registry);

        // Constructing these registers their counters and timers.
        IntelligenceActionEvaluator evaluator = new IntelligenceActionEvaluator(
                mock(ParsedProtocolCache.class), mock(ExpressionEvaluationService.class),
                mock(ActionDefinitionResolver.class), mock(IntelligenceTriggerProducer.class),
                mock(IntelligenceEventLogRepository.class), mock(DeviationRepository.class),
                new ObjectMapper(), mock(SlaThresholdReader.class), registry);

        MatcherEngine matcherEngine = new MatcherEngine(
                mock(MatcherEventLogService.class), mock(ResourceInfoExtractor.class),
                mock(TriggerMatchingService.class), mock(ExpressionEvaluationService.class),
                mock(ProtocolDefinitionService.class), mock(ProtocolInstanceService.class),
                mock(StepInstanceService.class), mock(ParsedProtocolCache.class),
                evaluator, mock(ClinicalEventTimeExtractor.class), registry);

        new IntelligenceTriggerProducer(
                mock(KafkaTemplate.class), mock(KafkaTopicProperties.class), registry, 5000);
        new InboundEventConsumer(matcherEngine, mock(FacilityService.class), registry);
    }

    @Test
    void eventsProcessedCounter_isRegistered() {
        Counter counter = registry.find("cce.events.processed").counter();
        assertNotNull(counter);
        counter.increment();
        assertEquals(1.0, counter.count());
    }

    @Test
    void eventsMatchedCounter_hasMatchedTag() {
        assertNotNull(registry.find("cce.events.matched").tag("status", "matched").counter());
    }

    @Test
    void eventsMatchedCounter_hasZeroMatchTag() {
        assertNotNull(registry.find("cce.events.matched").tag("status", "zero_match").counter());
    }

    @Test
    void eventsDuplicateCounter_isRegistered() {
        assertNotNull(registry.find("cce.events.duplicate").counter());
    }

    @Test
    void intelligencePublishedCounter_isRegistered() {
        assertNotNull(registry.find("cce.events.intelligence.published").counter());
    }

    @Test
    void intelligenceActionsEvaluatedCounter_isRegistered() {
        assertNotNull(registry.find("cce.intelligence.actions.evaluated").counter());
    }

    @Test
    void intelligenceActionsFiredCounter_isRegistered() {
        assertNotNull(registry.find("cce.intelligence.actions.fired").counter());
    }

    @Test
    void inboundConsumerErrorCounter_isRegistered() {
        assertNotNull(registry.find("cce.consumer.inbound.errors").counter());
    }

    @Test
    void intelligencePublishDurationTimer_isRegistered() {
        Timer timer = registry.find("cce.intelligence.publish.duration").timer();
        assertNotNull(timer);
        assertEquals(0, timer.count());
    }

    @Test
    void actionDefinitionsActiveGauge_isRegistered() {
        Gauge gauge = registry.find("cce.action.definitions.active").gauge();
        assertNotNull(gauge);
        assertEquals(2.0, gauge.value());
    }

    @Test
    void stepMatchingDurationTimer_isRegistered() {
        Timer timer = registry.find("cce.step.matching.duration").timer();
        assertNotNull(timer);
        assertEquals(0, timer.count());
    }

    @Test
    void eventProcessingDurationTimer_isRegistered() {
        Timer timer = registry.find("cce.events.processing.duration").timer();
        assertNotNull(timer);
        assertEquals(0, timer.count());
    }

    @Test
    void protocolInstancesActiveGauge_isRegistered() {
        Gauge gauge = registry.find("cce.protocol.instances.active").gauge();
        assertNotNull(gauge);
        assertEquals(3.0, gauge.value());
    }

    @Test
    void protocolInstancesActiveGauge_reflectsUpdates() {
        ProtocolInstanceRepository repo = mock(ProtocolInstanceRepository.class);
        when(repo.countByStatus(ProtocolInstanceStatus.ACTIVE)).thenReturn(0L).thenReturn(10L);
        ActionDefinitionRepository actionDefRepo = mock(ActionDefinitionRepository.class);
        when(actionDefRepo.countByStatus(ActionDefinitionStatus.ACTIVE)).thenReturn(0L);

        MeterRegistry freshRegistry = new SimpleMeterRegistry();
        new ObservabilityConfig().cceMetrics(repo, actionDefRepo).bindTo(freshRegistry);

        Gauge gauge = freshRegistry.find("cce.protocol.instances.active").gauge();
        assertNotNull(gauge);
        assertEquals(0.0, gauge.value());
        // Second call returns 10
        assertEquals(10.0, gauge.value());
    }
}
