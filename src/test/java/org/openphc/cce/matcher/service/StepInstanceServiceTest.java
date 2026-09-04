package org.openphc.cce.matcher.service;

import org.openphc.cce.common.sla.SlaThresholdReader;
import org.openphc.cce.common.deviation.DeviationRecorder;
import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.history.StateTransitionHistoryWriter;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.*;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepInstanceServiceTest {

    @Mock
    private StepInstanceRepository stepInstanceRepository;

    @Mock
    private ParsedProtocolCache parsedProtocolCache;

    @Mock
    private DeviationRecorder deviationRecorder;


    @Mock
    private IntelligenceActionEvaluator intelligenceActionEvaluator;

    @Mock
    private StateTransitionHistoryWriter stateTransitionHistoryWriter;

    @Mock
    private StepSlaScheduleService slaScheduleService;

    // Reads are the shared cce-common-util implementation; only the write path stays in matcher.
    @Mock
    private SlaThresholdReader slaThresholdReader;

    private StepInstanceService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new StepInstanceService(stepInstanceRepository,
                parsedProtocolCache, deviationRecorder,
                intelligenceActionEvaluator, stateTransitionHistoryWriter, slaScheduleService,
                slaThresholdReader);

        // Default: no SLA thresholds. Tests that care stub them per step via buildStep/stubThresholds.
        lenient().when(slaThresholdReader.getThresholds(any()))
                .thenReturn(new SlaThresholdReader.SlaThresholds(null, null));

        // Default: a protocol with no steps, so tests that only assert SLA/status outcomes need not
        // describe a graph. Tests that care override this via stubProtocol.
        lenient().when(parsedProtocolCache.get(any(), any()))
                .thenReturn(new ParsedProtocolCache.ParsedProtocol(
                        List.of(), PlanDefinitionParser.buildDependencyGraph(List.of())));
    }

    /** Stub the parsed-protocol cache to return {@code steps} and the graph derived from them. */
    private void stubProtocol(List<PlanDefinitionParser.StepMetadata> steps) {
        when(parsedProtocolCache.get(any(), any())).thenReturn(
                new ParsedProtocolCache.ParsedProtocol(
                        steps, PlanDefinitionParser.buildDependencyGraph(steps)));
    }

    @Nested
    class CreateStep {

        @Test
        void createsStepNotStartedAndSlaPending() {
            ProtocolInstance protocolInstance = buildProtocolInstance();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            OffsetDateTime missedDate = dueDate.plusDays(3);

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            StepInstance result = service.createStep(protocolInstance, "bp-check", 0,
                    dueDate, missedDate, "must");

            assertNotNull(result.getId());
            assertEquals("bp-check", result.getActionId());
            assertEquals(0, result.getRepeatIndex());
            assertEquals(StepStatus.NOT_STARTED, result.getStepStatus());
            assertNull(result.getSlaStatus(), "sla_status is null until a threshold falls due");
            // The thresholds are scheduled as step_sla_state_transition rows, not stored on the step.
            verify(slaScheduleService).schedule(result, dueDate, missedDate);
            // The initial NOT_STARTED/PENDING status is recorded in append-only history.
            verify(stateTransitionHistoryWriter).recordStepInstanceTransition(eq(result), any());
        }
    }

    @Nested
    class CompleteStep {

        @Test
        void completion_recordsTheEventButLeavesTheSlaUnjudged() {
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStep(StepStatus.NOT_STARTED, null, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            UUID eventId = UUID.randomUUID();
            service.completeStep(step, eventId, "test-source", null);

            assertEquals(StepStatus.COMPLETED, step.getStepStatus());
            // Matcher records that the work happened and when; whether that was timely is the
            // Step SLA Service's judgement, made when the due date falls and compared against
            // the completed_at recorded here. Writing MET here would be a second writer on the column.
            assertNull(step.getSlaStatus(), "sla_status is Step SLA's to write, not Matcher's");
            assertNotNull(step.getCompletedAt());
            assertEquals(eventId, step.getMatchedEventId());
            assertEquals("test-source", step.getCompletedBySource());

            // The COMPLETED transition is recorded in append-only history.
            verify(stateTransitionHistoryWriter).recordStepInstanceTransition(eq(step), any(OffsetDateTime.class));
        }

        @Test
        void completedAfterDueButBeforeMissed_slaStaysOverdue() {
            OffsetDateTime pastDue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
            OffsetDateTime futureMissed = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5);
            StepInstance step = buildStep(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, pastDue, futureMissed);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            // Recorded late, so the breach stands even though the work is now done
            assertEquals(StepStatus.COMPLETED, step.getStepStatus());
            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
        }

        @Test
        void completedAfterMissedDate_slaStaysMissed() {
            OffsetDateTime pastDue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
            OffsetDateTime pastMissed = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
            StepInstance step = buildStep(StepStatus.NOT_STARTED, SlaStatus.MISSED, pastDue, pastMissed);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            // The pairing the old single-column model could not express: done, but missed
            assertEquals(StepStatus.COMPLETED, step.getStepStatus());
            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
        }

        @Test
        void alreadyCompleted_throwsIllegalState() {
            StepInstance step = buildStep(StepStatus.COMPLETED, SlaStatus.MET, null, null);

            assertThrows(IllegalStateException.class,
                    () -> service.completeStep(step, UUID.randomUUID(), "src", null));
        }

        @Test
        void slaAlreadyMissed_isStillCompletableByALateEvent() {
            // Completability depends on StepStatus alone. Under the old single-column model a MISSED
            // step was terminal, so a late event created a second row instead of recording the
            // arrival against the step that was actually missed.
            OffsetDateTime pastDue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
            StepInstance step = buildStep(StepStatus.NOT_STARTED, SlaStatus.MISSED,
                    pastDue, pastDue.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            assertDoesNotThrow(() -> service.completeStep(step, UUID.randomUUID(), "src", null));

            assertEquals(StepStatus.COMPLETED, step.getStepStatus());
            assertEquals(SlaStatus.MISSED, step.getSlaStatus(), "the breach must survive the completion");
            assertNotNull(step.getCompletedAt());
        }

        @Test
        void optionalStepClosedOutWithSlaMet_isStillCompletableByALateEvent() {
            // An optional step closed out without an event keeps NOT_STARTED, so a later arrival is
            // recorded against it rather than spawning a duplicate row.
            OffsetDateTime futureDue = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5);
            StepInstance step = buildStep(StepStatus.NOT_STARTED, SlaStatus.MET, futureDue, null);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            assertDoesNotThrow(() -> service.completeStep(step, UUID.randomUUID(), "src", null));

            assertEquals(StepStatus.COMPLETED, step.getStepStatus());
            assertEquals(SlaStatus.MET, step.getSlaStatus());
        }
    }

    @Nested
    class ProgressiveInstantiation {

        @Test
        void completedStepWithRelatedActions_afterEnd_useCompletedAt() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepStatus.NOT_STARTED, null, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            // Mock parser to return action metadata with relatedActions

            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(), null, null, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("initial-enrollment", "after-end",
                                    BigDecimal.valueOf(7), "d")),
                            null, 3, "must", List.of()));
            stubProtocol(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            // Verify: the completed step + the dependent step
            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeast(2)).save(captor.capture());

            List<StepInstance> savedSteps = captor.getAllValues();
            StepInstance dependentStep = savedSteps.stream()
                    .filter(s -> "bp-check".equals(s.getActionId()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(dependentStep, "Dependent step bp-check should be created");
            assertEquals(StepStatus.NOT_STARTED, dependentStep.getStepStatus());
            assertNull(dependentStep.getSlaStatus(), "sla_status is null until a threshold falls due");
            SlaThresholdReader.SlaThresholds scheduled = scheduledFor("bp-check");
            assertNotNull(scheduled.dueDate());
            assertNotNull(scheduled.missedDate());
            // after-end uses completedAt as base — the due date should be ~7 days from completedAt
            assertTrue(scheduled.dueDate().isAfter(step.getCompletedAt().plusDays(6)));
        }

        @Test
        void completedStepWithRelatedActions_afterStart_useDueDate() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepStatus.NOT_STARTED, null, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));


            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(), null, null, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("initial-enrollment", "after-start",
                                    BigDecimal.valueOf(14), "d")),
                            null, 3, "must", List.of()));
            stubProtocol(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeast(2)).save(captor.capture());

            StepInstance dependentStep = captor.getAllValues().stream()
                    .filter(s -> "bp-check".equals(s.getActionId()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(dependentStep, "Dependent step bp-check should be created");
            // after-start uses the predecessor's scheduled due date as base
            OffsetDateTime expectedDue = dueDate.plusDays(14);
            assertEquals(expectedDue.toLocalDate(), scheduledFor("bp-check").dueDate().toLocalDate());
        }

        @Test
        void recurringSteps_createsMultipleInstances() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepStatus.NOT_STARTED, null, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));


            // Target action has timing: count=3, period=7 days
            PlanDefinitionParser.TimingInfo timing = new PlanDefinitionParser.TimingInfo(
                    3, 1, BigDecimal.valueOf(7), "d");

            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(), null, null, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("initial-enrollment", "after-end",
                                    BigDecimal.valueOf(7), "d")),
                            timing, 3, "must", List.of()));
            stubProtocol(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeast(4)).save(captor.capture());

            List<StepInstance> bpSteps = captor.getAllValues().stream()
                    .filter(s -> "bp-check".equals(s.getActionId()))
                    .toList();

            assertEquals(3, bpSteps.size(), "Should create 3 recurring step instances");

            // Verify repeat indices
            assertEquals(0, bpSteps.get(0).getRepeatIndex());
            assertEquals(1, bpSteps.get(1).getRepeatIndex());
            assertEquals(2, bpSteps.get(2).getRepeatIndex());

            // Verify staggered due dates (each 7 days apart)
            List<SlaThresholdReader.SlaThresholds> bpSchedules = scheduledForAll("bp-check");
            OffsetDateTime firstDue = bpSchedules.get(0).dueDate();
            assertEquals(firstDue.plusDays(7).toLocalDate(), bpSchedules.get(1).dueDate().toLocalDate());
            assertEquals(firstDue.plusDays(14).toLocalDate(), bpSchedules.get(2).dueDate().toLocalDate());

            // Each should have overdue and missed dates
            for (StepInstance s : bpSteps) {
                assertNotNull(scheduledForAll("bp-check").get(bpSteps.indexOf(s)).missedDate());
            }
        }

        @Test
        void relatedStepAlreadyExists_isNotRecreated() {
            // Regression: a target step may already exist (created reactively by its own
            // trigger, or a redelivered event). Progressive instantiation must NOT create a
            // duplicate — otherwise the duplicate goes overdue/missed and raises a spurious
            // deviation. This also guarantees nesting (organizational only) never spawns
            // duplicate parent/sibling steps.
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepStatus.NOT_STARTED, null, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            // A bp-check step already exists for this instance
            when(stepInstanceRepository.existsByProtocolInstanceIdAndActionId(
                    protocolInstance.getId(), "bp-check")).thenReturn(true);


            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(), null, null, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("initial-enrollment", "after-end",
                                    BigDecimal.valueOf(7), "d")),
                            null, 3, "must", List.of()));
            stubProtocol(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeastOnce()).save(captor.capture());

            boolean createdDuplicate = captor.getAllValues().stream()
                    .anyMatch(s -> "bp-check".equals(s.getActionId()));
            assertFalse(createdDuplicate, "Existing bp-check step must not be re-created");
        }

        /**
         * Fan-in: `review` depends on both `intake` (just completed) and `labs`. It must wait for
         * `labs` while that is still in flight, so its due date anchors to the last prerequisite
         * to finish rather than whichever completed first.
         */
        private List<PlanDefinitionParser.StepMetadata> fanInGraph() {
            return List.of(
                    new PlanDefinitionParser.StepMetadata("intake", "Intake",
                            List.of(), List.of(), null, null, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("labs", "Labs",
                            List.of(), List.of(), null, null, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("review", "Review",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("intake", "after-end",
                                    BigDecimal.valueOf(2), "d"),
                            new PlanDefinitionParser.RelatedStepInfo("labs", "after-end",
                                    BigDecimal.valueOf(2), "d")),
                            null, 1, "must", List.of()));
        }

        private List<StepInstance> completeIntakeWithLabsIn(ProtocolInstance protocolInstance,
                                                            SlaStatus labsSlaStatus) {
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance intake = buildStepWithProtocol(protocolInstance, "intake",
                    StepStatus.NOT_STARTED, null, dueDate, dueDate.plusDays(3));
            StepInstance labs = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("labs")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.NOT_STARTED)
                    .slaStatus(labsSlaStatus)
                    .requiredBehavior("must")
                    .build();

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any()))
                    .thenReturn(List.of(intake, labs));

            stubProtocol(fanInGraph());

            service.completeStep(intake, UUID.randomUUID(), "test-source", null);

            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeastOnce()).save(captor.capture());
            return captor.getAllValues();
        }

        @Test
        void beforeRelationship_onThePredecessor_stillInstantiatesTheDependent() {
            // A protocol may state the ordering from the other end: visit-encounter declares
            // "before vitals-recording" rather than vitals-recording declaring "after-end
            // visit-encounter". Both must create vitals-recording when visit-encounter completes.
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "visit-encounter",
                    StepStatus.NOT_STARTED, null, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            stubProtocol(List.of(
                    new PlanDefinitionParser.StepMetadata("visit-encounter", "Visit",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("vitals-recording", "before",
                                    BigDecimal.valueOf(2), "d")),
                            null, null, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("vitals-recording", "Vitals",
                            List.of(), List.of(), null, 1, "must", List.of())));

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeast(2)).save(captor.capture());

            StepInstance dependent = captor.getAllValues().stream()
                    .filter(s -> "vitals-recording".equals(s.getActionId()))
                    .findFirst().orElse(null);

            assertNotNull(dependent, "a 'before' edge on the predecessor must still create the dependent");
            assertEquals(StepStatus.NOT_STARTED, dependent.getStepStatus());
            assertNull(dependent.getSlaStatus(), "sla_status is null until a threshold falls due");
            // before-* normalizes to after-end: anchored to the predecessor's completion + 2d
            assertEquals(step.getCompletedAt().plusDays(2).toLocalDate(),
                    scheduledFor(dependent.getActionId()).dueDate().toLocalDate());
        }

        @Test
        void fanIn_otherPrerequisiteStillInFlight_defersCreation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            List<StepInstance> saved = completeIntakeWithLabsIn(protocolInstance, SlaStatus.OVERDUE);

            assertFalse(saved.stream().anyMatch(s -> "review".equals(s.getActionId())),
                    "review must wait while its other prerequisite (labs) is still DUE");
        }

        @Test
        void fanIn_otherPrerequisiteTerminal_createsImmediately() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // labs already MISSED — it will never complete, so waiting would strand review
            List<StepInstance> saved = completeIntakeWithLabsIn(protocolInstance, SlaStatus.MISSED);

            assertTrue(saved.stream().anyMatch(s -> "review".equals(s.getActionId())),
                    "review must be created once no prerequisite can still complete");
        }
    }

    @Nested
    class OffsetUnits {

        /**
         * The FHIR timing unit on a {@code relatedAction} offset decides when the dependent step is
         * due, so a unit read as the wrong duration silently moves every SLA on that edge.
         */
        @ParameterizedTest(name = "offset of 3{0} from completion")
        @CsvSource({"min,MINUTES,3", "h,HOURS,3", "d,DAYS,3", "wk,DAYS,21"})
        void fixedLengthUnitsAreConvertedToTheirDuration(String unit, ChronoUnit expectedUnit, long expectedAmount) {
            OffsetDateTime completedAt = dueDateFor(unit, 3);

            assertEquals(completedAt.plus(expectedAmount, expectedUnit), scheduledFor("bp-check").dueDate());
        }

        @Test
        void monthsAreCalendarMonthsRatherThanThirtyDays() {
            // plusMonths keeps the day-of-month, so a step due "1 month" after a 31 January
            // completion falls at the end of February rather than 2 March.
            OffsetDateTime completedAt = dueDateFor("mo", 1);

            assertEquals(completedAt.plusMonths(1), scheduledFor("bp-check").dueDate());
        }

        @Test
        void yearsAreCalendarYears() {
            OffsetDateTime completedAt = dueDateFor("a", 2);

            assertEquals(completedAt.plusYears(2), scheduledFor("bp-check").dueDate());
        }

        @Test
        void anOffsetWithNoUnitFallsBackToTheBaseTime() {
            // Rather than guessing a unit and scheduling the step at an arbitrary distance, the
            // dependent step becomes due as soon as its prerequisite completes.
            OffsetDateTime completedAt = dueDateFor(null, 5);

            assertEquals(completedAt, scheduledFor("bp-check").dueDate());
        }

        @Test
        void noOffsetAtAllMakesTheStepDueOnCompletion() {
            OffsetDateTime completedAt = dueDateFor("d", null);

            assertEquals(completedAt, scheduledFor("bp-check").dueDate());
        }

        @Test
        void anUnrecognizedUnitIsRejectedRatherThanSilentlyIgnored() {
            // Treating an unknown unit as zero would schedule the step immediately and look like a
            // working protocol; failing here surfaces the bad definition instead.
            assertThrows(IllegalArgumentException.class, () -> dueDateFor("fortnight", 2));
        }

        /**
         * Completes a prerequisite whose dependent step carries the given offset, and returns the
         * completion time the offset is measured from.
         */
        private OffsetDateTime dueDateFor(String unit, Integer amount) {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepStatus.NOT_STARTED, null, dueDate, dueDate.plusDays(3));

            lenient().when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            stubProtocol(List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(), null, null, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(new PlanDefinitionParser.RelatedStepInfo(
                                    "initial-enrollment", "after-end",
                                    amount == null ? null : BigDecimal.valueOf(amount), unit)),
                            null, 3, "must", List.of())));

            service.completeStep(step, UUID.randomUUID(), "test-source", null);
            return step.getCompletedAt();
        }
    }

    @Nested
    class OrderViolationDetection {

        @Test
        void completingStep_withIncompleteMustPredecessor_createsOrderViolation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // Predecessor step (vitals-recording) still PENDING
            StepInstance predecessorStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("vitals-recording")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.NOT_STARTED)
                    .requiredBehavior("must")                    .build();
            stubThresholds(predecessorStep, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), null);

            // Successor step (chief-complaints) being completed
            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("chief-complaints")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.NOT_STARTED)
                    .requiredBehavior("must")                    .build();
            stubThresholds(completedStep, OffsetDateTime.now(ZoneOffset.UTC).plusDays(7), null);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(predecessorStep, completedStep));


            // vitals-recording has relatedAction pointing to chief-complaints
            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("vitals-recording", "Vitals",
                            List.of(), List.of(), null, 1, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("chief-complaints", "Chief Complaints",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("vitals-recording", "after-end",
                                    BigDecimal.ZERO, "d")),
                            null, 1, "must", List.of()));
            stubProtocol(actions);

            Deviation deviation = Deviation.builder().id(UUID.randomUUID()).build();
            when(deviationRecorder.recordDeviation(any(), eq(DeviationType.ORDER_VIOLATION), any()))
                    .thenReturn(new DeviationRecorder.DeviationResult(deviation, true));

            service.completeStep(completedStep, UUID.randomUUID(), "test-source", null);

            verify(deviationRecorder).recordDeviation(
                    eq(completedStep), eq(DeviationType.ORDER_VIOLATION),
                    argThat(metadata -> {
                        @SuppressWarnings("unchecked")
                        List<String> incomplete = (List<String>) metadata.get("incompletePrerequisites");
                        return incomplete != null && incomplete.contains("vitals-recording");
                    }));
            verify(intelligenceActionEvaluator).evaluateOnDeviation(completedStep, deviation);
        }

        @Test
        void completingStep_withCompletedMustPredecessor_noOrderViolation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // Predecessor step already COMPLETED
            StepInstance predecessorStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("vitals-recording")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.COMPLETED)
                    .slaStatus(SlaStatus.MET)
                    .requiredBehavior("must")
                    .build();

            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("chief-complaints")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.NOT_STARTED)
                    .requiredBehavior("must")                    .build();
            stubThresholds(completedStep, OffsetDateTime.now(ZoneOffset.UTC).plusDays(7), null);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(predecessorStep, completedStep));


            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("vitals-recording", "Vitals",
                            List.of(), List.of(), null, 1, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("chief-complaints", "Chief Complaints",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("vitals-recording", "after-end",
                                    BigDecimal.ZERO, "d")),
                            null, 1, "must", List.of()));
            stubProtocol(actions);

            service.completeStep(completedStep, UUID.randomUUID(), "test-source", null);

            verify(deviationRecorder, never()).recordDeviation(
                    any(), eq(DeviationType.ORDER_VIOLATION), any());
        }

        @Test
        void completingStep_withIncompleteCouldPredecessor_noOrderViolation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // Predecessor step is optional (could) and still PENDING — not a violation
            StepInstance predecessorStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("history-assessment")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.NOT_STARTED)
                    .requiredBehavior("could")
                    .build();

            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("lab-order")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.NOT_STARTED)
                    .requiredBehavior("must")                    .build();
            stubThresholds(completedStep, OffsetDateTime.now(ZoneOffset.UTC).plusDays(7), null);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(predecessorStep, completedStep));


            // lab-order comes after history-assessment (could)
            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("history-assessment", "History",
                            List.of(), List.of(), null, 1, "could", List.of()),
                    new PlanDefinitionParser.StepMetadata("lab-order", "Lab Order",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("history-assessment", "after-end",
                                    BigDecimal.ZERO, "d")),
                            null, 1, "must", List.of()));
            stubProtocol(actions);

            service.completeStep(completedStep, UUID.randomUUID(), "test-source", null);

            verify(deviationRecorder, never()).recordDeviation(
                    any(), eq(DeviationType.ORDER_VIOLATION), any());
        }

        @Test
        void completingFirstStep_noPredecessors_noOrderViolation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // First step in chain — no predecessors
            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("visit-encounter")
                    .repeatIndex(0)
                    .stepStatus(StepStatus.NOT_STARTED)
                    .requiredBehavior("must")                    .build();
            stubThresholds(completedStep, OffsetDateTime.now(ZoneOffset.UTC).plusDays(7), null);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(completedStep));


            // visit-encounter declares no prerequisite — it heads the chain
            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("visit-encounter", "Visit",
                            List.of(), List.of(), null, 1, "must", List.of()),
                    new PlanDefinitionParser.StepMetadata("vitals-recording", "Vitals",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("visit-encounter", "after-start",
                                    BigDecimal.ZERO, "d")),
                            null, 1, "must", List.of()));
            stubProtocol(actions);

            service.completeStep(completedStep, UUID.randomUUID(), "test-source", null);

            verify(deviationRecorder, never()).recordDeviation(
                    any(), eq(DeviationType.ORDER_VIOLATION), any());
        }
    }

    @Nested
    class BackfillMissingMandatorySteps {

        /**
         * The emr-service-protocol journey: an explicit forward chain. Nesting is not modelled here
         * because it carries no ordering of its own — the chain is what {@code relatedAction}
         * declares. Mandatory ("must"): vitals-recording, consultation, diagnosis.
         */
        private List<PlanDefinitionParser.StepMetadata> emrJourney() {
            return List.of(
                    action("visit-encounter", null, null),
                    action("vitals-recording", "visit-encounter", "must"),
                    action("consultation", "vitals-recording", "must"),
                    action("chief-complaints", "consultation", null),
                    action("history-assessment", "chief-complaints", "could"),
                    action("lab-order", "history-assessment", null),
                    action("lab-results", "lab-order", null),
                    action("diagnosis", "lab-results", "must"),
                    action("treatment", "diagnosis", null),
                    action("referral", "treatment", "could"));
        }

        private PlanDefinitionParser.StepMetadata action(String id, String prerequisiteActionId,
                                                         String requiredBehavior) {
            return action(id, prerequisiteActionId, requiredBehavior, 1);
        }

        /** A step that comes after {@code prerequisiteActionId} — relatedAction names what it depends on. */
        private PlanDefinitionParser.StepMetadata action(String id, String prerequisiteActionId,
                                                         String requiredBehavior,
                                                         Integer toleranceDays) {
            List<PlanDefinitionParser.RelatedStepInfo> related = prerequisiteActionId == null
                    ? List.of()
                    : List.of(new PlanDefinitionParser.RelatedStepInfo(
                            prerequisiteActionId, "after-end", BigDecimal.ZERO, "d"));
            return new PlanDefinitionParser.StepMetadata(id, id, List.of(), related,
                    null, toleranceDays, requiredBehavior, List.of());
        }

        private void stubGraph(List<PlanDefinitionParser.StepMetadata> steps) {
            stubProtocol(steps);
        }

        private Map<String, StepInstance> capturedStepsExcept(String... actionIds) {
            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeastOnce()).save(captor.capture());
            Set<String> excluded = Set.of(actionIds);
            return captor.getAllValues().stream()
                    .filter(s -> !excluded.contains(s.getActionId()))
                    .collect(Collectors.toMap(StepInstance::getActionId, s -> s, (a, b) -> a));
        }

        @Test
        void stepCompletedWithNoPrecedingRows_backfillsMandatoryStepsAsPending() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

            // Only `treatment` was ever recorded — its predecessors have no step_instance row,
            // so the journey view shows them as "not started" and the scheduler cannot see them.
            StepInstance treatment = buildStepWithProtocol(protocolInstance, "treatment",
                    StepStatus.NOT_STARTED, null, occurredAt.minusHours(2), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(treatment));
            stubGraph(emrJourney());

            service.completeStep(treatment, UUID.randomUUID(), "ebuzima", occurredAt);

            Map<String, StepInstance> backfilled = capturedStepsExcept("treatment");

            // Mandatory only: history-assessment/referral are `could`, and visit-encounter,
            // chief-complaints, lab-order, lab-results declare no requiredBehavior.
            assertEquals(Set.of("vitals-recording", "consultation", "diagnosis"), backfilled.keySet());
            backfilled.values().forEach(step -> {
                assertEquals(StepStatus.NOT_STARTED, step.getStepStatus());
                assertNull(step.getSlaStatus(), "sla_status is null until a threshold falls due");
                assertEquals("must", step.getRequiredBehavior());
                assertEquals(0, step.getRepeatIndex());
                // Anchored to the clinical completion time, with tolerance-derived thresholds so
                // the evaluator drives them OVERDUE then MISSED if they are never recorded.
                SlaThresholdReader.SlaThresholds scheduled = scheduledFor(step.getActionId());
                assertEquals(treatment.getCompletedAt(), scheduled.dueDate());
                assertEquals(treatment.getCompletedAt().plusDays(1), scheduled.missedDate());
            });
        }

        @Test
        void mandatoryStepWithExistingTerminalRow_notBackfilledAgain() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance missedDiagnosis = buildStepWithProtocol(protocolInstance, "diagnosis",
                    StepStatus.NOT_STARTED, SlaStatus.MISSED, occurredAt.minusDays(5), occurredAt.minusDays(4));
            StepInstance treatment = buildStepWithProtocol(protocolInstance, "treatment",
                    StepStatus.NOT_STARTED, null, occurredAt.minusHours(2), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(missedDiagnosis, treatment));
            stubGraph(emrJourney());

            service.completeStep(treatment, UUID.randomUUID(), "ebuzima", occurredAt);

            // A mandatory action with any row — here a terminal MISSED one — is left alone.
            assertEquals(Set.of("vitals-recording", "consultation"),
                    capturedStepsExcept("treatment", "diagnosis").keySet());
        }

        @Test
        void inOrderCompletion_noMandatoryGap_backfillsNothing() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance visit = buildStepWithProtocol(protocolInstance, "visit-encounter",
                    StepStatus.COMPLETED, SlaStatus.MET, occurredAt.minusHours(3), occurredAt.minusHours(2));
            StepInstance vitals = buildStepWithProtocol(protocolInstance, "vitals-recording",
                    StepStatus.NOT_STARTED, null, occurredAt.minusHours(1), occurredAt.plusDays(1));
            vitals.setRequiredBehavior("must");

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(visit, vitals));
            stubGraph(emrJourney());

            service.completeStep(vitals, UUID.randomUUID(), "ebuzima", occurredAt);

            // `consultation` is created by progressive instantiation (vitals-recording's dependent),
            // and nothing else — no mandatory action is missing at this point in the journey.
            assertEquals(Set.of("consultation"), capturedStepsExcept("vitals-recording").keySet());
        }

        @Test
        void completingNestedSubStep_doesNotBackfillMandatoryStepStillAheadInChain() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance vitals = buildStepWithProtocol(protocolInstance, "vitals-recording",
                    StepStatus.COMPLETED, SlaStatus.MET, occurredAt.minusHours(3), occurredAt.minusHours(2));
            StepInstance consultation = buildStepWithProtocol(protocolInstance, "consultation",
                    StepStatus.COMPLETED, SlaStatus.MET, occurredAt.minusHours(2), occurredAt.minusHours(1));
            StepInstance chiefComplaints = buildStepWithProtocol(protocolInstance, "chief-complaints",
                    StepStatus.NOT_STARTED, null, occurredAt.minusHours(1), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(vitals, consultation, chiefComplaints));
            stubGraph(emrJourney());

            service.completeStep(chiefComplaints, UUID.randomUUID(), "ebuzima", occurredAt);

            // `diagnosis` is mandatory and shares the completed step's nesting group, but the
            // forward chain has not reached it — backfilling it here would stamp it with this
            // completion's time and flatten the schedule its own relatedAction offsets define.
            // It stays out until progress past it appears (or its own trigger fires), and protocol
            // completion is gated on it regardless. `history-assessment` is `could`, so progressive
            // instantiation skips it too — nothing at all is created.
            assertTrue(capturedStepsExcept("chief-complaints").isEmpty());
        }

        @Test
        void mandatoryStepAheadIsBackfilledOnceProgressPastItAppears() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance vitals = buildStepWithProtocol(protocolInstance, "vitals-recording",
                    StepStatus.COMPLETED, SlaStatus.MET, occurredAt.minusHours(3), occurredAt.minusHours(2));
            StepInstance consultation = buildStepWithProtocol(protocolInstance, "consultation",
                    StepStatus.COMPLETED, SlaStatus.MET, occurredAt.minusHours(2), occurredAt.minusHours(1));
            StepInstance chiefComplaints = buildStepWithProtocol(protocolInstance, "chief-complaints",
                    StepStatus.COMPLETED, SlaStatus.MET, occurredAt.minusHours(1), occurredAt);
            // `treatment` arrives on its own trigger, skipping over `diagnosis`
            StepInstance treatment = buildStepWithProtocol(protocolInstance, "treatment",
                    StepStatus.NOT_STARTED, null, occurredAt.minusMinutes(30), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(vitals, consultation, chiefComplaints, treatment));
            stubGraph(emrJourney());

            service.completeStep(treatment, UUID.randomUUID(), "ebuzima", occurredAt);

            // `diagnosis` is now a predecessor of observed progress, so it is genuinely late and
            // gets materialized.
            assertEquals(Set.of("diagnosis"), capturedStepsExcept("treatment").keySet());
        }

        @Test
        void actionWithoutToleranceDays_backfilledWithoutOverdueAndMissedDates() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance consultation = buildStepWithProtocol(protocolInstance, "consultation",
                    StepStatus.NOT_STARTED, null, occurredAt.minusHours(1), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(consultation));
            stubGraph(List.of(
                    action("vitals-recording", null, "must", null),
                    action("consultation", "vitals-recording", "must", null)));

            service.completeStep(consultation, UUID.randomUUID(), "ebuzima", occurredAt);

            StepInstance backfilled = capturedStepsExcept("consultation").get("vitals-recording");
            assertNotNull(backfilled);
            assertEquals(StepStatus.NOT_STARTED, backfilled.getStepStatus());
            assertNull(backfilled.getSlaStatus(), "sla_status is null until a threshold falls due");
            SlaThresholdReader.SlaThresholds scheduled = scheduledFor("vitals-recording");
            assertEquals(consultation.getCompletedAt(), scheduled.dueDate());
            // No tolerance-days extension: there is no MISSED_DATE_REACHED row to schedule.
            assertNull(scheduled.missedDate());
        }
    }

    // ── Helpers ──

    private ProtocolInstance buildProtocolInstance() {
        return ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .status(ProtocolInstanceStatus.ACTIVE)
                .build();
    }

    private ProtocolInstance buildProtocolInstanceWithDefinition() {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .definition(objectMapper.createObjectNode().put("resourceType", "PlanDefinition"))
                .build();

        return ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .protocolDefinition(protocolDef)
                .status(ProtocolInstanceStatus.ACTIVE)
                .build();
    }

    private StepInstance buildStep(StepStatus stepStatus, SlaStatus slaStatus,
                                   OffsetDateTime dueDate, OffsetDateTime missedDate) {
        ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
        StepInstance step = StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId("action-1")
                .repeatIndex(0)
                .stepStatus(stepStatus)
                .slaStatus(slaStatus)
                .build();
        stubThresholds(step, dueDate, missedDate);
        return step;
    }

    private StepInstance buildStepWithProtocol(ProtocolInstance protocolInstance, String actionId,
                                               StepStatus stepStatus, SlaStatus slaStatus,
                                               OffsetDateTime dueDate, OffsetDateTime missedDate) {
        StepInstance step = StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(0)
                .stepStatus(stepStatus)
                .slaStatus(slaStatus)
                .build();
        stubThresholds(step, dueDate, missedDate);
        return step;
    }

    /**
     * The thresholds {@code createStep} scheduled for the step with the given actionId — captured from
     * the schedule call, since they are no longer stored on the step itself.
     */
    private SlaThresholdReader.SlaThresholds scheduledFor(String actionId) {
        return scheduledForAll(actionId).stream().findFirst().orElse(null);
    }

    /** Every set of thresholds scheduled for {@code actionId}, in creation order (recurring steps). */
    private List<SlaThresholdReader.SlaThresholds> scheduledForAll(String actionId) {
        ArgumentCaptor<StepInstance> stepCaptor = ArgumentCaptor.forClass(StepInstance.class);
        ArgumentCaptor<OffsetDateTime> dueCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> missedCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(slaScheduleService, atLeastOnce())
                .schedule(stepCaptor.capture(), dueCaptor.capture(), missedCaptor.capture());

        List<SlaThresholdReader.SlaThresholds> matches = new ArrayList<>();
        List<StepInstance> steps = stepCaptor.getAllValues();
        for (int i = 0; i < steps.size(); i++) {
            if (actionId.equals(steps.get(i).getActionId())) {
                matches.add(new SlaThresholdReader.SlaThresholds(
                        dueCaptor.getAllValues().get(i), missedCaptor.getAllValues().get(i)));
            }
        }
        return matches;
    }

    /** Stand in for the step_sla_state_transition rows this step would have been scheduled with. */
    private void stubThresholds(StepInstance step, OffsetDateTime dueDate, OffsetDateTime missedDate) {
        lenient().when(slaThresholdReader.getThresholds(step.getId()))
                .thenReturn(new SlaThresholdReader.SlaThresholds(dueDate, missedDate));
    }
}
