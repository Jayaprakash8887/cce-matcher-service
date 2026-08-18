package org.openphc.cce.matcher.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.common.event.CloudEventMessage;
import org.openphc.cce.matcher.service.MatcherEngine;
import org.openphc.cce.matcher.service.FacilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for inbound clinical events from the Collector Service.
 * Each event triggers two independent operations: facility reference capture
 * (best-effort, non-fatal) followed by clinical protocol processing (fatal on failure).
 */
@Component
public class InboundEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboundEventConsumer.class);

    private final MatcherEngine matcherEngine;
    private final FacilityService facilityService;
    private final Counter errorCounter;

    public InboundEventConsumer(MatcherEngine matcherEngine,
                                FacilityService facilityService,
                                MeterRegistry meterRegistry) {
        this.matcherEngine = matcherEngine;
        this.facilityService = facilityService;
        this.errorCounter = Counter.builder("cce.consumer.inbound.errors")
                .description("Inbound event consumption failures routed to retry/DLQ")
                .register(meterRegistry);
    }

    /**
     * Receives every inbound clinical event and runs two independent concerns before the Kafka
     * offset is committed.
     *
     * <p>{@link FacilityService#upsertFacility} is called here rather than inside the
     * {@code @Transactional} {@link MatcherEngine} so the facility row commits in its own
     * transaction and survives an engine failure, and so it runs ahead of the engine's duplicate
     * check. Its failures are non-fatal: a transient error on a reference table must not send a
     * clinical event to the DLQ.
     *
     * <p>{@link MatcherEngine#processInboundEvent} failures are fatal by design — the exception
     * propagates to the container's error handler, which retries with backoff and then DLQs.
     *
     * @see <a href="../../../../../../../docs/architecture-overview.md">Architecture overview</a>
     */
    @KafkaListener(topics = "${cce.kafka.topics.inbound-events}")
    public void consume(CloudEventMessage event) {
        MDC.put("correlationId", event.getCorrelationid());
        MDC.put("source", event.getSource());
        MDC.put("eventType", event.getType());
        MDC.put("subject", event.getSubject());
        try {
            log.debug("Received inbound event: cloudeventsId={}, source={}", event.getId(), event.getSource());
            try {
                facilityService.upsertFacility(event);
            } catch (Exception e) {
                log.warn("Facility registration failed for facilityId={} — matcher processing will continue",
                        event.getFacilityid(), e);
            }
            matcherEngine.processInboundEvent(event);
        } catch (Exception e) {
            errorCounter.increment();
            throw e; // Propagate to DefaultErrorHandler for retry + DLQ
        } finally {
            MDC.clear();
        }
    }
}
