package org.openphc.cce.matcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.matcher.domain.entity.MatcherEventLog;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.ProcessingStatus;
import org.openphc.cce.matcher.domain.repository.MatcherEventLogRepository;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.repository.StepInstanceRepository;
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
 * Integration tests for the inbound event processing pipeline:
 * Kafka → Consumer → MatcherEngine → DB.
 */
class InboundEventIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private MatcherEventLogRepository eventLogRepository;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Autowired
    private StepInstanceRepository stepInstanceRepository;

    @Value("${cce.kafka.topics.inbound-events}")
    private String inboundTopic;

    private UUID protocolDefId;

    @BeforeEach
    void seedReadModel() throws Exception {
        protocolDefId = seedProtocol(
                "src/test/resources/fhir/plan-definition-anc-high-risk.json", "10.0.0-inbound");
    }

    @Test
    void matchingEvent_createsEnrollmentAndStep() {
        String eventId = UUID.randomUUID().toString();
        String patientId = "patient-inbound-" + UUID.randomUUID();

        // An Encounter event matches "any-encounter-log" (F1 only — resource type match)
        CloudEventMessage event = buildEncounterEvent(eventId, patientId, "in-progress");

        kafkaTemplate.send(inboundTopic, event);

        // Wait for event to be processed
        await().atMost(30, SECONDS).untilAsserted(() -> {
            List<MatcherEventLog> logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId()))
                    .toList();
            assertThat(logs).isNotEmpty();
            assertThat(logs.get(0).getProcessingStatus()).isEqualTo(ProcessingStatus.MATCHED);
        });

        // Verify enrollment
        List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId())).toList();
        assertThat(instances).isNotEmpty();

        // Verify step created
        List<StepInstance> steps = stepInstanceRepository.findByProtocolInstanceId(instances.get(0).getId());
        assertThat(steps).isNotEmpty();
        assertThat(steps.get(0).getActionId()).isEqualTo("any-encounter-log");
    }

    @Test
    void duplicateEvent_recordedAsDuplicate() {
        String eventId = "dup-" + UUID.randomUUID();
        String patientId = "patient-dup-" + UUID.randomUUID();

        CloudEventMessage event = buildEncounterEvent(eventId, patientId, "in-progress");

        // Send first
        kafkaTemplate.send(inboundTopic, event);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            List<MatcherEventLog> logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId()))
                    .toList();
            assertThat(logs).isNotEmpty();
        });

        // Send duplicate
        kafkaTemplate.send(inboundTopic, event);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            List<MatcherEventLog> logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId())
                            && el.getProcessingStatus() == ProcessingStatus.DUPLICATE)
                    .toList();
            assertThat(logs).isNotEmpty();
        });
    }

    @Test
    void nonMatchingEvent_recordedAsZeroMatch() {
        String eventId = "nomatch-" + UUID.randomUUID();
        String patientId = "patient-nomatch-" + UUID.randomUUID();

        // Send a Medication resource — no trigger matches this type
        ObjectNode eventData = objectMapper.createObjectNode();
        eventData.put("resourceType", "Medication");
        eventData.put("status", "active");

        CloudEventMessage event = CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test")
                .type("org.openphc.fhir.Medication.create")
                .specversion("1.0")
                .subject(patientId)
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/json")
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(eventData)
                .build();

        kafkaTemplate.send(inboundTopic, event);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            List<MatcherEventLog> logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId()))
                    .toList();
            assertThat(logs).isNotEmpty();
            assertThat(logs.get(0).getProcessingStatus()).isEqualTo(ProcessingStatus.ZERO_MATCH);
        });
    }

    @Test
    void observationEvent_matchesBpCheck() {
        String eventId = "bp-" + UUID.randomUUID();
        String patientId = "patient-bp-" + UUID.randomUUID();

        // First, enroll patient by sending an Encounter (matches any-encounter-log)
        String enrollEventId = "enroll-bp-" + UUID.randomUUID();
        kafkaTemplate.send(inboundTopic, buildEncounterEvent(enrollEventId, patientId, "in-progress"));

        await().atMost(30, SECONDS).untilAsserted(() -> {
            assertThat(protocolInstanceRepository.findAll().stream()
                    .filter(p -> patientId.equals(p.getPatientId())).toList()).isNotEmpty();
        });

        // Now send a blood-pressure Observation (LOINC 85354-9)
        ObjectNode codeNode = objectMapper.createObjectNode();
        codeNode.set("coding", objectMapper.createArrayNode().add(
                objectMapper.createObjectNode()
                        .put("system", "http://loinc.org")
                        .put("code", "85354-9")));

        ObjectNode eventData = objectMapper.createObjectNode();
        eventData.put("resourceType", "Observation");
        eventData.put("status", "final");
        eventData.set("code", codeNode);
        eventData.put("subject", patientId);

        CloudEventMessage bpEvent = CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test")
                .type("org.openphc.fhir.Observation.create")
                .specversion("1.0")
                .subject(patientId)
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/json")
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(eventData)
                .build();

        kafkaTemplate.send(inboundTopic, bpEvent);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            List<MatcherEventLog> logs = eventLogRepository.findAll().stream()
                    .filter(el -> eventId.equals(el.getCloudeventsId()))
                    .toList();
            assertThat(logs).isNotEmpty();
            assertThat(logs.get(0).getProcessingStatus()).isEqualTo(ProcessingStatus.MATCHED);
        });

        // Verify a blood-pressure-check step was created
        List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId())).toList();
        assertThat(instances).isNotEmpty();
        List<StepInstance> allSteps = stepInstanceRepository.findByProtocolInstanceId(instances.get(0).getId());
        assertThat(allSteps.stream().map(s -> s.getActionId()))
                .contains("blood-pressure-check");
    }

    private CloudEventMessage buildEncounterEvent(String eventId, String patientId, String status) {
        ObjectNode eventData = objectMapper.createObjectNode();
        eventData.put("resourceType", "Encounter");
        eventData.put("status", status);
        eventData.put("id", UUID.randomUUID().toString());

        return CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test")
                .type("org.openphc.fhir.Encounter.create")
                .specversion("1.0")
                .subject(patientId)
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/json")
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(eventData)
                .build();
    }
}
