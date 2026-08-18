package org.openphc.cce.matcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.entity.AuditLog;
import org.openphc.cce.common.repository.AuditLogRepository;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.event.CloudEventMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the audit subsystem.
 *
 * <p>Scoped to the events Matcher itself audits — enrolment, matching and step completion. Protocol
 * load and retire are audited by the protocol-management service that now owns them.
 */
class AuditIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Value("${cce.kafka.topics.inbound-events}")
    private String inboundTopic;

    @Test
    void eventMatch_createsMatcherAuditRecords() throws Exception {
        seedProtocol("src/test/resources/fhir/plan-definition-anc-high-risk.json",
                "41.0.0-audit-" + UUID.randomUUID().toString().substring(0, 8));

        String patientId = "patient-audit-" + UUID.randomUUID();
        String eventId = "audit-event-" + UUID.randomUUID();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Encounter");
        data.put("status", "in-progress");

        CloudEventMessage event = CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test-audit")
                .type("org.openphc.fhir.Encounter.create")
                .specversion("1.0")
                .subject(patientId)
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/json")
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(data)
                .build();

        kafkaTemplate.send(inboundTopic, event);

        // Wait for enrollment
        await().atMost(30, SECONDS).untilAsserted(() ->
                assertThat(protocolInstanceRepository.findAll().stream()
                        .filter(p -> patientId.equals(p.getPatientId())).toList()).isNotEmpty());

        // Audit rows are written after the producing transaction commits
        await().atMost(10, SECONDS).untilAsserted(() -> {
            List<AuditLog> matcherLogs = auditLogRepository.findByEventCategory("MATCHER");
            assertThat(matcherLogs).anyMatch(log ->
                    "PROTOCOL_ENROLLED".equals(log.getEventType()));
            assertThat(matcherLogs).anyMatch(log ->
                    "EVENT_MATCHED".equals(log.getEventType()));
            assertThat(matcherLogs).anyMatch(log ->
                    "STEP_COMPLETED".equals(log.getEventType()));
        });
    }
}
