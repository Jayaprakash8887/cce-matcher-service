package org.openphc.cce.matcher.service;

import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
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
import org.openphc.cce.common.fhir.FhirExpressionEvaluator;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.fhir.ResourceTypeDetector;
import org.openphc.cce.common.fhir.UnsupportedExpressionLanguageException;
import org.openphc.cce.common.event.CloudEventMessage;
import org.openphc.cce.common.fhir.ClinicalEventTimeExtractor;

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
    @Mock private ResourceTypeDetector resourceTypeDetector;
    @Mock private EventCodesExtractor eventCodesExtractor;
    @Mock private TriggerMatchingService triggerMatchingService;
    @Mock private FhirExpressionEvaluator fhirExpressionEvaluator;
    @Mock private ProtocolDefinitionService protocolDefinitionService;
    @Mock private ProtocolInstanceService protocolInstanceService;
    @Mock private StepInstanceService stepInstanceService;
    @Mock private ParsedProtocolCache parsedProtocolCache;
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
        engine = new MatcherEngine(eventLogService, resourceTypeDetector, eventCodesExtractor,
                triggerMatchingService, fhirExpressionEvaluator,
                protocolDefinitionService, protocolInstanceService,
                stepInstanceService, parsedProtocolCache,
                intelligenceActionEvaluator, clinicalEventTimeExtractor, meterRegistry);
    }

    @Nested
    class DuplicateEvent {

        @Test
        void duplicateEvent_skipsProcessing_andWritesNoSecondLogRow() {
            // (cloudevents_id, source) is unique in matcher_event_log, so a second row for the same
            // event would violate it and send the event round the retry loop to the dead-letter topic.
            CloudEventMessage event = buildEvent();
            when(eventLogService.isDuplicate(event.getId(), event.getSource())).thenReturn(true);

            engine.processInboundEvent(event);

            verify(eventLogService, never()).recordEvent(any(), any());
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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Observation);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Observation);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "bp-check")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            mockParserReturnsNoCondition(protocolDef, "bp-check");
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(eq(protocolInstance.getId()), eq("bp-check"), any()))
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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Observation);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "bp-check")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            mockParserReturnsNoCondition(protocolDef, "bp-check");
            // The FHIR payload resolves to a clinical occurrence time distinct from the envelope/processing clock.
            when(clinicalEventTimeExtractor.extract(eq(ResourceType.Observation), any())).thenReturn(clinicalTime);
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(eq(protocolInstance.getId()), eq("bp-check"), any()))
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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Encounter);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
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
            when(stepInstanceService.findActionableStep(eq(instance1.getId()), eq("action-a"), any())).thenReturn(step1);
            when(stepInstanceService.findActionableStep(eq(instance2.getId()), eq("action-b"), any())).thenReturn(step2);

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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Observation);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(any(), any())).thenReturn(List.of());
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of(
                    new org.openphc.cce.matcher.service.ConditionOnlyTrigger(
                            protocolDefId, "condition-action", "text/jsonlogic", "{\"==\": [1, 1]}")));
            when(fhirExpressionEvaluator.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 1]}"), any()))
                    .thenReturn(true);
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            mockParserReturnsNoCondition(protocolDef, "condition-action");
            when(stepInstanceService.findActionableStep(eq(protocolInstance.getId()), eq("condition-action"), any()))
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
            mockParserReturnsNoCondition(protocolDef, "explicit-action");
            when(stepInstanceService.findActionableStep(eq(protocolInstanceId), eq("explicit-action"), any()))
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
            when(stepInstanceService.findActionableStep(eq(protocolInstanceId), eq("new-action"), any())).thenReturn(null);
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

        @Test
        void explicitMatch_aStepInAGroup_isCreatedInItsGroupsOccurrence() {
            UUID protocolInstanceId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(UUID.randomUUID());
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            protocolInstance.setId(protocolInstanceId);
            StepInstance newStep = buildActionableStep(protocolInstance, "bp");

            CloudEventMessage event = buildEvent();
            event.setActionid("bp");
            event.setProtocolinstanceid(protocolInstanceId.toString());
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(protocolInstanceService.findById(protocolInstanceId)).thenReturn(protocolInstance);
            PlanDefinitionParser.StepMetadata bp = new PlanDefinitionParser.StepMetadata("bp", "BP",
                    List.of(), List.of(), null, null, "must", List.of(), "visit-2");
            stubProtocol(List.of(bp));
            when(stepInstanceService.findOrCreateGroupedStep(eq(protocolInstance), eq(bp), any(), any()))
                    .thenReturn(newStep);

            engine.processInboundEvent(event);

            // The group decides which occurrence the event goes to before any row is looked for.
            verify(stepInstanceService, never()).findActionableStep(any(), any(), any());
            verify(stepInstanceService, never()).createStep(any(), any(), anyInt(), any(), any(), any());
            verify(stepInstanceService).completeStep(eq(newStep), eq(eventLog.getId()), eq(event.getSource()), any());
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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Observation);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "follow-up")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            mockParserReturnsNoCondition(protocolDef, "follow-up");
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(existingInstance);
            when(stepInstanceService.findActionableStep(eq(existingInstance.getId()), eq("follow-up"), any()))
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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Observation);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Observation), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "conditional-action")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            // No findById stub: the candidate is filtered out before enrollment, and the parsed-protocol
            // cache takes the definition JSON as a supplier that a cache hit never invokes.

            // Parser returns action with a condition that fails
            mockParserReturnsWithCondition(protocolDef, "conditional-action",
                    "text/jsonlogic", "{\"==\": [1, 0]}");
            when(fhirExpressionEvaluator.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 0]}"), any()))
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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Observation);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(any(), any())).thenReturn(List.of());
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of(
                    new org.openphc.cce.matcher.service.ConditionOnlyTrigger(
                            protocolDefId, "action-cql", "text/cql", "some-expression")));
            when(fhirExpressionEvaluator.evaluate(eq("text/cql"), any(), any()))
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
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Encounter);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
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
            when(stepInstanceService.findActionableStep(eq(protocolInstance.getId()), eq("first-step"), any()))
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
    class FutureDatedEvent {

        // A source whose clock runs ahead, or that labels local time as UTC (Tiberbu sends Kenyan time
        // with +00:00, three hours ahead), sends an occurrence time in the future. The step the event
        // creates is due the moment it is created; completing it must not land after that moment, or
        // the step reads as late (no MET row, an OVERDUE deviation) for work recorded on time.

        @Test
        void initialStep_isCompletedAtItsDueDate_andEnrolmentIsNotInTheFuture() {
            OffsetDateTime threeHoursAhead = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3);
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(protocolDefId);
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            StepInstance newStep = buildActionableStep(protocolInstance, "consent-request");

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Consent);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Consent), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "consent-request")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            stubProtocol(List.of(
                    new PlanDefinitionParser.StepMetadata("consent-request", "Consent Requested",
                            List.of(new PlanDefinitionParser.TriggerInfo(List.of(), null)),
                            List.of(), null, null, "must", List.of())));
            when(clinicalEventTimeExtractor.extract(eq(ResourceType.Consent), any())).thenReturn(threeHoursAhead);
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(eq(protocolInstance.getId()), eq("consent-request"), any()))
                    .thenReturn(null);
            when(stepInstanceService.createStep(eq(protocolInstance), eq("consent-request"),
                    eq(0), any(), any(), eq("must"))).thenReturn(newStep);

            engine.processInboundEvent(event);
            OffsetDateTime afterProcessing = OffsetDateTime.now(ZoneOffset.UTC);

            ArgumentCaptor<OffsetDateTime> dueDate = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(stepInstanceService).createStep(eq(protocolInstance), eq("consent-request"),
                    eq(0), dueDate.capture(), any(), eq("must"));
            ArgumentCaptor<OffsetDateTime> completedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(stepInstanceService).completeStep(eq(newStep), eq(eventLog.getId()), eq(event.getSource()),
                    completedAt.capture());
            ArgumentCaptor<OffsetDateTime> enrolledAt = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(protocolInstanceService).enrollPatient(eq("patient-1"), eq(protocolDef), enrolledAt.capture());

            assertEquals(dueDate.getValue(), completedAt.getValue(),
                    "a step completed by the event that created it must land on its due date, not after it");
            assertFalse(completedAt.getValue().isAfter(afterProcessing), "completed_at must not be in the future");
            assertEquals(completedAt.getValue(), enrolledAt.getValue(), "enrolled_at must not be in the future either");
        }

        @Test
        void explicitMatch_newStep_isCompletedAtItsDueDate() {
            OffsetDateTime threeHoursAhead = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3);
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
            when(stepInstanceService.findActionableStep(eq(protocolInstanceId), eq("new-action"), any())).thenReturn(null);
            mockParserReturnsActionForInitialStep(protocolDef, "new-action", 3, "must");
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Observation);
            when(clinicalEventTimeExtractor.extract(eq(ResourceType.Observation), any())).thenReturn(threeHoursAhead);
            when(stepInstanceService.createStep(eq(protocolInstance), eq("new-action"),
                    eq(0), any(), any(), eq("must"))).thenReturn(newStep);

            engine.processInboundEvent(event);

            ArgumentCaptor<OffsetDateTime> dueDate = ArgumentCaptor.forClass(OffsetDateTime.class);
            ArgumentCaptor<OffsetDateTime> missedDate = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(stepInstanceService).createStep(eq(protocolInstance), eq("new-action"),
                    eq(0), dueDate.capture(), missedDate.capture(), eq("must"));
            ArgumentCaptor<OffsetDateTime> completedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(stepInstanceService).completeStep(eq(newStep), eq(eventLog.getId()), eq(event.getSource()),
                    completedAt.capture());

            assertEquals(dueDate.getValue(), completedAt.getValue());
            assertEquals(dueDate.getValue().plusDays(3), missedDate.getValue(), "missed date stays due date + tolerance");
        }

        @Test
        void pastOccurrenceTime_isKept_andLandsBeforeTheDueDate() {
            OffsetDateTime anHourAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
            CloudEventMessage event = buildEvent();
            MatcherEventLog eventLog = buildEventLog(ProcessingStatus.ZERO_MATCH);
            UUID protocolDefId = UUID.randomUUID();
            ProtocolDefinition protocolDef = buildProtocolDefinition(protocolDefId);
            ProtocolInstance protocolInstance = buildProtocolInstance(protocolDef);
            StepInstance newStep = buildActionableStep(protocolInstance, "first-step");

            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(false);
            when(eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH)).thenReturn(eventLog);
            when(resourceTypeDetector.detect(event.getData())).thenReturn(ResourceType.Encounter);
            when(eventCodesExtractor.extractCodes(event.getData())).thenReturn(List.of());
            when(triggerMatchingService.findStructuralMatches(eq(ResourceType.Encounter), any()))
                    .thenReturn(List.of(new MatchedStep(protocolDefId, "first-step")));
            when(triggerMatchingService.getConditionOnlyTriggers()).thenReturn(List.of());
            when(protocolDefinitionService.findById(protocolDefId)).thenReturn(protocolDef);
            stubProtocol(List.of(
                    new PlanDefinitionParser.StepMetadata("first-step", "First Step",
                            List.of(new PlanDefinitionParser.TriggerInfo(List.of(), null)),
                            List.of(), null, null, "must", List.of())));
            when(clinicalEventTimeExtractor.extract(eq(ResourceType.Encounter), any())).thenReturn(anHourAgo);
            when(protocolInstanceService.enrollPatient(eq("patient-1"), eq(protocolDef), any()))
                    .thenReturn(protocolInstance);
            when(stepInstanceService.findActionableStep(eq(protocolInstance.getId()), eq("first-step"), any()))
                    .thenReturn(null);
            when(stepInstanceService.createStep(eq(protocolInstance), eq("first-step"),
                    eq(0), any(), any(), eq("must"))).thenReturn(newStep);

            engine.processInboundEvent(event);

            ArgumentCaptor<OffsetDateTime> dueDate = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(stepInstanceService).createStep(eq(protocolInstance), eq("first-step"),
                    eq(0), dueDate.capture(), any(), eq("must"));
            ArgumentCaptor<OffsetDateTime> completedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(stepInstanceService).completeStep(eq(newStep), eq(eventLog.getId()), eq(event.getSource()),
                    completedAt.capture());

            assertEquals(anHourAgo, completedAt.getValue(), "a past occurrence time is the completion time as it is");
            assertTrue(completedAt.getValue().isBefore(dueDate.getValue()));
        }
    }

    @Nested
    class Metrics {

        @Test
        void metricsAreRecordedForProcessedEvents() {
            CloudEventMessage event = buildEvent();
            when(eventLogService.isDuplicate(anyString(), anyString())).thenReturn(true);

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
