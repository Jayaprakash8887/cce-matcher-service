package org.openphc.cce.matcher.service;

import org.openphc.cce.common.service.AuditService;
import org.openphc.cce.common.service.IntelligenceActionEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hl7.fhir.r4.model.ResourceType;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.matcher.domain.entity.MatcherEventLog;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.ProcessingStatus;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.fhir.ExpressionEvaluationService;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.fhir.UnsupportedExpressionLanguageException;
import org.openphc.cce.common.event.CloudEventMessage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatcherEngineTest {

    @Mock private MatcherEventLogService eventLogService;
    @Mock private ResourceInfoExtractor resourceInfoExtractor;
    @Mock private TriggerMatchingService triggerMatchingService;
    @Mock private ExpressionEvaluationService expressionEvaluationService;
    @Mock private ProtocolDefinitionService protocolDefinitionService;
    @Mock private ProtocolInstanceService protocolInstanceService;
    @Mock private StepInstanceService stepInstanceService;
    @Mock private ParsedProtocolCache parsedProtocolCache;
    @Mock private AuditService auditService;
    @Mock private IntelligenceActionEvaluator intelligenceActionEvaluator;
    @Mock private ClinicalEventTimeExtractor clinicalEventTimeExtractor;

    private MeterRegistry meterRegistry;
    private MatcherEngine engine;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        engine = new MatcherEngine(eventLogService, resourceInfoExtractor,
                triggerMatchingService, expressionEvaluationService,
                protocolDefinitionService, protocolInstanceService,
                stepInstanceService, parsedProtocolCache, auditService,
                intelligenceActionEvaluator, clinicalEventTimeExtractor, meterRegistry);
    }

    @Nested
    class DuplicateEvent {

        @Test
        void duplicateEvent_skipProcessing_duplicateStatus() {
            CloudEventMessage event = buildEvent();
            when(eventLogService.isDuplicate(event.getId(), event.getSource())).thenReturn(true);
            when(eventLogService.recordEvent(event, ProcessingStatus.DUPLICATE))
                    .thenReturn(buildEventLog(ProcessingStatus.DUPLICATE));

            engine.processInboundEvent(event);

            verify(eventLogService).recordEvent(event, ProcessingStatus.DUPLICATE);
            verify(triggerMatchingService, never()).findStructuralMatches(any(), any());
            verify(stepInstanceService, never()).completeStep(any(), any(), any(), any());

            assertEquals(1.0, meterRegistry.counter("cce.events.duplicate").count());
        }
    }

    @Nested
    class ZeroMatch {

        @Test
        void noMatches_zeroMatchStatusLogged() {
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Observation);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any())).thenReturn(List.of());
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());

            engine.processInboundEvent(event);

            verify(eventLogService, never()).updateStatus(any(), eq(ProcessingStatus.MATCHED));
            assertEquals(1.0, meterRegistry.counter("cce.events.matched", "status", "zero_match").count());
        }
    }

    @Nested
    class SingleMatch {

        @Test
        void singleMatch_enrollmentAndStepCompletion() {
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(protocolDefId);
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            StepInstance step = buildActionableStep(protocolInstance, "bp-check");

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Observation);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "bp-check")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            mockParserReturnsNoCondition(protocolDef, "bp-check");
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(protocolInstance.getId(), "bp-check"))
                    .thenReturn(step);

            engine.processInboundEvent(event);

            verify(protocolInstanceService).enrollPatient(eq("patient-1"), eq(protocolDef), any());
            verify(stepInstanceService).completeStep(eq(step), eq(eventLog.getId()), eq(event.getSource()), any());
            verify(intelligenceActionEvaluator).evaluateOnCompletion(eq(step), any());
            verify(eventLogService).updateStatus(eventLog, ProcessingStatus.MATCHED);
            assertEquals(1.0, meterRegistry.counter("cce.events.matched", "status", "matched").count());
        }

        @Test
        void enrollment_usesClinicalOccurrenceTimeNotProcessingClock() {
            // enrolled_at must be based on when the clinical act happened (event_time), not now(),
            // so downstream cohort date-filtering reflects clinical enrollment. Regression guard for
            // the fix that repointed enrollment from OffsetDateTime.now() to resolveOccurredAt(event).
            OffsetDateTime clinicalTime = OffsetDateTime.of(2026, 3, 15, 10, 0, 0, 0, ZoneOffset.UTC);
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(protocolDefId);
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            StepInstance step = buildActionableStep(protocolInstance, "bp-check");

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Observation);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "bp-check")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            mockParserReturnsNoCondition(protocolDef, "bp-check");
            // The FHIR payload resolves to a clinical occurrence time distinct from the envelope/processing clock.
            when(clinicalEventTimeExtractor.extract(eq(ResourceType.Observation), any())).thenReturn(clinicalTime);
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(protocolInstance.getId(), "bp-check"))
                    .thenReturn(step);

            engine.processInboundEvent(event);

            ArgumentCaptor<OffsetDateTime> enrolledAt = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(protocolInstanceService).enrollPatient(eq("patient-1"), eq(protocolDef), enrolledAt.capture());
            assertEquals(clinicalTime, enrolledAt.getValue(),
                    "enrolled_at must be the clinical occurrence time, not the processing clock");
        }
    }

    @Nested
    class MultipleMatches {

        @Test
        void multipleMatches_eachCreatesOwnStep() {
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId1 = UUID.randomUUID();
            UUID protocolDefId2 = UUID.randomUUID();
            ProtocolDefinition protocolDef1 = buildProtocolDefinition(protocolDefId1);
            ProtocolDefinition protocolDef2 = buildProtocolDefinition(protocolDefId2);
            ProtocolInstance instance1 = buildProtocolInstance(protocolDef1);
            ProtocolInstance instance2 = buildProtocolInstance(protocolDef2);
            StepInstance step1 = buildActionableStep(instance1, "action-a");
            StepInstance step2 = buildActionableStep(instance2, "action-b");

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Encounter);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Encounter), any()))
                    .thenReturn(List.of(
                            new MatchedStep(protocolDefId1, "action-a"),
                            new MatchedStep(protocolDefId2, "action-b")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId1)).thenReturn(protocolDef1);
            when(protocolDefinitionService.findById(protocolDefId2)).thenReturn(protocolDef2);

            // Use anyString() since both definitions have same JSON; return action list covering both
            stubProtocol(List.of(
                    new PlanDefinitionParser.StepMetadata("action-a", "Action A",
                            List.of(new PlanDefinitionParser.TriggerInfo(List.of(), null)),
                            List.of(), null, null, null, List.of()),
                    new PlanDefinitionParser.StepMetadata("action-b", "Action B",
                            List.of(new PlanDefinitionParser.TriggerInfo(List.of(), null)),
                            List.of(), null, null, null, List.of())));
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef1), any()))
                    .thenReturn(instance1);
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef2), any()))
                    .thenReturn(instance2);
            when(stepInstanceService.findActionableStep(instance1.getId(), "action-a")).thenReturn(step1);
            when(stepInstanceService.findActionableStep(instance2.getId(), "action-b")).thenReturn(step2);

            engine.processInboundEvent(event);

            verify(stepInstanceService).completeStep(eq(step1), eq(eventLog.getId()), eq(event.getSource()), any());
            verify(stepInstanceService).completeStep(eq(step2), eq(eventLog.getId()), eq(event.getSource()), any());
            verify(intelligenceActionEvaluator).evaluateOnCompletion(eq(step1), any());
            verify(intelligenceActionEvaluator).evaluateOnCompletion(eq(step2), any());
            verify(eventLogService).updateStatus(eventLog, ProcessingStatus.MATCHED);
        }
    }

    @Nested
    class ConditionOnlyTrigger {

        @Test
        void conditionOnlyTrigger_matchedViaTier2() {
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(protocolDefId);
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            StepInstance step = buildActionableStep(protocolInstance, "condition-action");

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Observation);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(any(), any())).thenReturn(List.of());
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of(
                    new org.openphc.cce.matcher.service.ConditionOnlyTrigger(
                            protocolDefId, "condition-action", "text/jsonlogic", "{\"==\": [1, 1]}")));
            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 1]}"), any()))
                    .thenReturn(true);
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(protocolInstance.getId(), "condition-action"))
                    .thenReturn(step);

            engine.processInboundEvent(event);

            verify(stepInstanceService).completeStep(eq(step), eq(eventLog.getId()), eq(event.getSource()), any());
            verify(intelligenceActionEvaluator).evaluateOnCompletion(eq(step), any());
            verify(eventLogService).updateStatus(eventLog, ProcessingStatus.MATCHED);
        }
    }

    @Nested
    class ExplicitMatch {

        @Test
        void explicitMatch_bypassesTierMatching() {
            UUID protocolInstanceId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(UUID.randomUUID());
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            protocolInstance.setId(protocolInstanceId);
            StepInstance step = buildActionableStep(protocolInstance, "explicit-action");

            CloudEventMessage event = buildEvent();
            event.setActionid("explicit-action");
            event.setProtocolinstanceid(protocolInstanceId.toString());

            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(protocolInstanceService.findById(protocolInstanceId)).thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(protocolInstanceId, "explicit-action"))
                    .thenReturn(step);

            engine.processInboundEvent(event);

            verify(stepInstanceService).completeStep(eq(step), eq(eventLog.getId()), eq(event.getSource()), any());
            verify(intelligenceActionEvaluator).evaluateOnCompletion(eq(step), any());
            verify(eventLogService).updateStatus(eventLog, ProcessingStatus.MATCHED);
            // Should NOT call tier matching
            verify(triggerMatchingService, never()).findStructuralMatches(any(), any());
        }

        @Test
        void explicitMatch_noExistingStep_createsNewStep() {
            UUID protocolInstanceId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(UUID.randomUUID());
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            protocolInstance.setId(protocolInstanceId);
            StepInstance newStep = buildActionableStep(protocolInstance, "new-action");

            CloudEventMessage event = buildEvent();
            event.setActionid("new-action");
            event.setProtocolinstanceid(protocolInstanceId.toString());

            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(protocolInstanceService.findById(protocolInstanceId)).thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(protocolInstanceId, "new-action")).thenReturn(null);
            // createInitialStep reads the definition off the instance, so no findById stub is needed
            mockParserReturnsActionForInitialStep(protocolDef, "new-action", 3, "must");
            when(stepInstanceService.createStep(eq(protocolInstance), eq("new-action"),
                    eq(0), any(), any(), eq("must"))).thenReturn(newStep);

            engine.processInboundEvent(event);

            verify(stepInstanceService).createStep(eq(protocolInstance), eq("new-action"),
                    eq(0), any(), any(), eq("must"));
            verify(stepInstanceService).completeStep(eq(newStep), eq(eventLog.getId()), eq(event.getSource()), any());
            verify(intelligenceActionEvaluator).evaluateOnCompletion(eq(newStep), any());
        }
    }

    @Nested
    class ExistingEnrollment {

        @Test
        void existingEnrollment_stepCompletedWithoutReEnrollment() {
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(protocolDefId);
            ProtocolInstance existingInstance = buildProtocolInstance(protocolDef);
            StepInstance step = buildActionableStep(existingInstance, "follow-up");

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Observation);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "follow-up")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            mockParserReturnsNoCondition(protocolDef, "follow-up");
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(existingInstance);
            when(stepInstanceService.findActionableStep(existingInstance.getId(), "follow-up"))
                    .thenReturn(step);

            engine.processInboundEvent(event);

            // enrollPatient is called but returns existing instance (idempotent)
            verify(protocolInstanceService).enrollPatient(eq("patient-1"), eq(protocolDef), any());
            verify(stepInstanceService).completeStep(eq(step), eq(eventLog.getId()), eq(event.getSource()), any());
            verify(intelligenceActionEvaluator).evaluateOnCompletion(eq(step), any());
        }
    }

    @Nested
    class Tier2ConditionFails {

        @Test
        void tier2ConditionFails_candidateFilteredOut() {
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(protocolDefId);

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Observation);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "conditional-action")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            // No findById stub: the candidate is filtered out before enrollment, and the parsed-protocol
            // cache takes the definition JSON as a supplier that a cache hit never invokes.

            // Parser returns action with a condition that fails
            mockParserReturnsWithCondition(protocolDef, "conditional-action",
                    "text/jsonlogic", "{\"==\": [1, 0]}");
            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 0]}"), any()))
                    .thenReturn(false);

            engine.processInboundEvent(event);

            verify(stepInstanceService, never()).completeStep(any(), any(), any(), any());
            verify(eventLogService, never()).updateStatus(any(), eq(ProcessingStatus.MATCHED));
            assertEquals(1.0, meterRegistry.counter("cce.events.matched", "status", "zero_match").count());
        }
    }

    @Nested
    class UnsupportedExpression {

        @Test
        void unsupportedExpressionLanguage_propagatesException() {
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Observation);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(any(), any())).thenReturn(List.of());
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of(
                    new org.openphc.cce.matcher.service.ConditionOnlyTrigger(
                            protocolDefId, "action-cql", "text/cql", "some-expression")));
            when(expressionEvaluationService.evaluate(eq("text/cql"), any(), any()))
                    .thenThrow(new UnsupportedExpressionLanguageException("text/cql"));

            assertThrows(UnsupportedExpressionLanguageException.class,
                    () -> engine.processInboundEvent(event));
        }
    }

    @Nested
    class NoActionableStep {

        @Test
        void noActionableStep_createsNewInitialStep() {
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(protocolDefId);
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            StepInstance newStep = buildActionableStep(protocolInstance, "first-step");

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceInfoExtractor.extractResourceType(event.getData())).thenReturn(ResourceType.Encounter);
            when(resourceInfoExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Encounter), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "first-step")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            // Single parser mock that covers both Tier 2 check (needs triggers) and createInitialStep
            stubProtocol(List.of(
                    new PlanDefinitionParser.StepMetadata("first-step", "First Step",
                            List.of(new PlanDefinitionParser.TriggerInfo(List.of(), null)),
                            List.of(), null, 5, "must", List.of())));
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(protocolInstance.getId(), "first-step"))
                    .thenReturn(null);
            when(stepInstanceService.createStep(eq(protocolInstance), eq("first-step"),
                    eq(0), any(), any(), eq("must"))).thenReturn(newStep);

            engine.processInboundEvent(event);

            verify(stepInstanceService).createStep(eq(protocolInstance), eq("first-step"),
                    eq(0), any(), any(), eq("must"));
            verify(stepInstanceService).completeStep(eq(newStep), eq(eventLog.getId()), eq(event.getSource()), any());
        }
    }

    @Nested
    class Metrics {

        @Test
        void metricsAreRecordedForProcessedEvents() {
            CloudEventMessage event = buildEvent();
            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(true);
            when(eventLogService.recordEvent(event, ProcessingStatus.DUPLICATE))
                    .thenReturn(buildEventLog(ProcessingStatus.DUPLICATE));

            engine.processInboundEvent(event);

            assertEquals(1.0, meterRegistry.counter("cce.events.processed").count());
            assertEquals(1.0, meterRegistry.counter("cce.events.duplicate").count());
        }
    }

    // ── Helpers ──

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
                .data(objectMapper.valueToTree(Map.of("resourceType", "Observation",
                        "code", Map.of("coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "85354-9"))))))
                .build();
    }

    private MatcherEventLog buildEventLog(ProcessingStatus status) {
        return MatcherEventLog.builder()
                .id(UUID.randomUUID())
                .cloudeventsId("ce-" + UUID.randomUUID())
                .source("test-source")
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationId(UUID.randomUUID().toString())
                .processingStatus(status)
                .build();
    }

    private ProtocolDefinition buildProtocolDefinition(UUID id) {
        ObjectNode defNode = objectMapper.createObjectNode();
        defNode.put("resourceType", "PlanDefinition");
        defNode.put("url", "http://openphc.org/PlanDefinition/test-protocol");
        defNode.put("version", "1.0.0");

        return ProtocolDefinition.builder()
                .id(id)
                .url("http://openphc.org/PlanDefinition/test-protocol")
                .version("1.0.0")
                .definition(defNode)
                .build();
    }

    private ProtocolInstance buildProtocolInstance(ProtocolDefinition protocolDef) {
        return ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .protocolDefinition(protocolDef)
                .protocolCanonical(protocolDef.getUrl() + "|" + protocolDef.getVersion())
                .status(ProtocolInstanceStatus.ACTIVE)
                .enrolledAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private StepInstance buildActionableStep(ProtocolInstance protocolInstance, String actionId) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(0)
                .stepStatus(StepStatus.NOT_STARTED)
                .slaStatus(SlaStatus.PENDING)
                .build();
    }

    /**
     * Mocks parser to return action metadata with no condition for the given action ID.
     * Used for Tier 2 evaluation pass-through (Scenarios 1/2).
     */
    private void mockParserReturnsNoCondition(ProtocolDefinition protocolDef, String actionId) {
        stubProtocol(List.of(
                new PlanDefinitionParser.StepMetadata(actionId, "Test Action",
                        List.of(new PlanDefinitionParser.TriggerInfo(List.of(), null)),
                        List.of(), null, null, null, List.of())));
    }

    /**
     * Mocks parser to return action metadata with a condition for Tier 2 evaluation.
     */
    private void mockParserReturnsWithCondition(ProtocolDefinition protocolDef, String actionId,
                                                String language, String expression) {
        stubProtocol(List.of(
                new PlanDefinitionParser.StepMetadata(actionId, "Conditional Action",
                        List.of(new PlanDefinitionParser.TriggerInfo(List.of(),
                                new PlanDefinitionParser.ConditionInfo(language, expression))),
                        List.of(), null, null, null, List.of())));
    }

    /**
     * Mocks parser for initial step creation — returns action metadata with tolerance and requiredBehavior.
     */
    private void mockParserReturnsActionForInitialStep(ProtocolDefinition protocolDef, String actionId,
                                                       Integer toleranceDays, String requiredBehavior) {
        stubProtocol(List.of(
                new PlanDefinitionParser.StepMetadata(actionId, "Test Action",
                        List.of(), List.of(), null, toleranceDays, requiredBehavior, List.of())));
    }

    /** Stub the parsed-protocol cache with the given flattened steps. */
    private void stubProtocol(List<PlanDefinitionParser.StepMetadata> steps) {
        when(parsedProtocolCache.get(any(), any())).thenReturn(
                new ParsedProtocolCache.ParsedProtocol(
                        steps, PlanDefinitionParser.buildDependencyGraph(steps)));
    }
}
