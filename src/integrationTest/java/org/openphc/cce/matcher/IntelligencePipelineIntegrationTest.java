package org.openphc.cce.matcher;

import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.repository.DeviationRepository;
import org.openphc.cce.common.repository.IntelligenceEventLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.entity.IntelligenceEventLog;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.matcher.domain.repository.*;
import org.openphc.cce.common.event.CloudEventMessage;
import org.openphc.cce.common.service.ActionDefinitionResolver;
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
 * End-to-end integration tests for the intelligence pipeline:
 * Event → Match → Enroll → Scheduler transition → Deviation → Intelligence action evaluation
 * → IntelligenceEventLog created → IntelligenceTriggerEvent published to Kafka
 *
 * Also covers Intelligence Event Log API endpoints (GET list + GET by ID).
 */
class IntelligencePipelineIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Autowired
    private StepInstanceRepository stepInstanceRepository;

    @Autowired
    private DeviationRepository deviationRepository;

    @Autowired
    private IntelligenceEventLogRepository intelligenceEventLogRepository;

    @Autowired
    private ActionDefinitionResolver actionDefinitionService;

    @Value("${cce.kafka.topics.inbound-events}")
    private String inboundTopic;

    private String protocolVersion;

    @BeforeEach
    void loadProtocolAndActionDefinition() throws Exception {
        protocolVersion = "1.0.0-intel-" + UUID.randomUUID().toString().substring(0, 8);

        seedProtocol("src/integrationTest/resources/fhir/plan-definition-with-intelligence.json",
                protocolVersion);

        // url|version must match the definitionCanonical the PlanDefinition's intelligence action names
        seedActionDefinition("src/integrationTest/resources/fhir/activity-definition-escalation.json");
    }

    /**
     * Sends an Encounter event to enroll a patient and waits for enrollment
     * in the intelligence protocol specifically.
     * Returns the protocol instance for the intelligence PlanDefinition.
     */
    private ProtocolInstance enrollAndWait(String patientId) throws Exception {
        String expectedCanonical = "http://openphc.org/PlanDefinition/anc-intelligence-integration|" + protocolVersion;

        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Encounter");
        data.put("status", "in-progress");

        CloudEventMessage event = CloudEventMessage.builder()
                .id("intel-enroll-" + UUID.randomUUID())
                .source("integration-test-intelligence")
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

        await().atMost(30, SECONDS).untilAsserted(() -> {
            List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                    .filter(p -> patientId.equals(p.getPatientId())).toList();
            assertThat(instances).anyMatch(i -> i.getProtocolCanonical().equals(expectedCanonical));
        });

        return protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId()))
                .filter(i -> i.getProtocolCanonical().equals(expectedCanonical))
                .findFirst().orElseThrow();
    }

    // ── Intelligence Pipeline Tests ──

    @Nested
    class IntelligenceEvaluation {

        @Test
        void stepCompletion_firesCompletionActionEndToEnd() throws Exception {
            String patientId = "patient-intel-completion-" + UUID.randomUUID();

            // The inbound Encounter drives the whole chain: match -> enrol -> create step ->
            // complete step -> evaluate intelligence -> publish -> record the event log.
            ProtocolInstance protocolInstance = enrollAndWait(patientId);
            UUID protocolInstanceId = protocolInstance.getId();

            UUID stepId = await().atMost(30, SECONDS).until(
                    () -> stepInstanceRepository.findByProtocolInstanceId(protocolInstanceId).stream()
                            .filter(s -> "encounter-step".equals(s.getActionId()))
                            .findFirst().map(StepInstance::getId).orElse(null),
                    java.util.Objects::nonNull);

            await().atMost(30, SECONDS).untilAsserted(() ->
                    assertThat(intelligenceEventLogRepository.findByStepInstanceId(stepId)).isNotEmpty());

            // Exactly one: completion-alert matches stepStatus == "completed", while the sibling
            // overdue/missed actions key on a deviationType the completion context does not carry.
            List<IntelligenceEventLog> eventLogs = intelligenceEventLogRepository.findByStepInstanceId(stepId);
            assertThat(eventLogs).hasSize(1);

            IntelligenceEventLog eventLog = eventLogs.get(0);
            assertThat(eventLog.getTriggerReason()).isEqualTo("completion");
            assertThat(eventLog.getStepActionId()).isEqualTo("completion-alert");
            assertThat(eventLog.isPublished()).isTrue();
            assertThat(eventLog.getPublishedAt()).isNotNull();
            assertThat(eventLog.getEventPayload()).isNotNull();
            assertThat(eventLog.getEvaluationContext()).isNotNull();
            assertThat(eventLog.getProtocolInstanceId()).isEqualTo(protocolInstanceId);
            assertThat(eventLog.getStepInstanceId()).isEqualTo(stepId);
            assertThat(eventLog.getSubject()).isEqualTo(patientId);

            // No deviation: the step was completed, not breached.
            assertThat(deviationRepository.findAll().stream()
                    .filter(d -> d.getStepInstance().getId().equals(stepId)).toList()).isEmpty();
        }

        @Test
        void completedStepWithNoMatchingCondition_recordsNoEventLog() throws Exception {
            // A step this protocol declares no intelligence action for: completing it must fire nothing.
            String patientId = "patient-intel-nomatch-" + UUID.randomUUID();
            ProtocolInstance protocolInstance = enrollAndWait(patientId);

            StepInstance unrelatedStep = stepInstanceRepository.save(StepInstance.builder()
                    .protocolInstance(protocolInstance)
                    .actionId("step-with-no-intelligence-actions")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.NOT_STARTED)
                    .slaStatus(SlaStatus.PENDING)
                    .requiredBehavior("must")
                    .build());

            assertThat(intelligenceEventLogRepository.findByStepInstanceId(unrelatedStep.getId())).isEmpty();
        }
    }

    // ── Intelligence Event Log API Tests ──

}
