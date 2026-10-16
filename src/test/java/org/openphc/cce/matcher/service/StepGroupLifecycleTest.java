package org.openphc.cce.matcher.service;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Duration;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.PlanDefinition.ActionGroupingBehavior;
import org.hl7.fhir.r4.model.PlanDefinition.ActionRelationshipType;
import org.hl7.fhir.r4.model.PlanDefinition.ActionRequiredBehavior;
import org.hl7.fhir.r4.model.PlanDefinition.PlanDefinitionActionComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openphc.cce.common.deviation.DeviationRecorder;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.history.StateTransitionHistoryWriter;
import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.repository.StepGroupInstanceRepository;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.common.sla.SlaThresholdReader;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Step groups end to end through {@link StepInstanceService} and {@link StepGroupService}: opening an
 * occurrence, attaching steps, progressing inside it, and choosing which row an event completes. Groups
 * are never recorded as complete or closed yet. The repositories are in memory; the protocols are parsed
 * by the real parser.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepGroupLifecycleTest {

    // In the past: completeStep clamps a future clinical time to now.
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 10, 9, 0, 0, 0, ZoneOffset.UTC);

    private static FhirContext fhirContext;

    @Mock private StepInstanceRepository stepInstanceRepository;
    @Mock private StepGroupInstanceRepository groupInstanceRepository;
    @Mock private ProtocolInstanceRepository protocolInstanceRepository;
    @Mock private ParsedProtocolCache parsedProtocolCache;
    @Mock private DeviationRecorder deviationRecorder;
    @Mock private IntelligenceActionEvaluator intelligenceActionEvaluator;
    @Mock private StateTransitionHistoryWriter stateTransitionHistoryWriter;
    @Mock private StepSlaScheduleService slaScheduleService;
    @Mock private SlaThresholdReader slaThresholdReader;

    private final List<StepInstance> stepRows = new ArrayList<>();
    private final List<StepGroupInstance> groupRows = new ArrayList<>();

    private StepInstanceService service;
    private ProtocolInstance enrollment;
    private ParsedProtocolCache.ParsedProtocol protocol;

    @BeforeAll
    static void initFhirContext() {
        fhirContext = FhirContext.forR4();
    }

    @BeforeEach
    void setUp() {
        StepGroupService groupService = new StepGroupService(groupInstanceRepository, protocolInstanceRepository);
        service = new StepInstanceService(stepInstanceRepository, parsedProtocolCache, deviationRecorder,
                intelligenceActionEvaluator, stateTransitionHistoryWriter, slaScheduleService,
                slaThresholdReader, groupService, groupInstanceRepository);

        enrollment = ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .protocolDefinition(ProtocolDefinition.builder().id(UUID.randomUUID()).build())
                .status(ProtocolInstanceStatus.ACTIVE)
                .build();

        when(slaThresholdReader.getThresholds(any())).thenReturn(SlaThresholdReader.SlaThresholds.NONE);
        when(parsedProtocolCache.get(any(), any())).thenAnswer(i -> protocol);
        stubStepRows();
        stubGroupRows();
    }

    // ── In-memory repositories ──

    private void stubStepRows() {
        when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(i -> {
            StepInstance row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).plusNanos(stepRows.size()));
                stepRows.add(row);
            }
            return row;
        });
        when(stepInstanceRepository.findByProtocolInstanceId(any())).thenAnswer(i -> List.copyOf(stepRows));
        when(stepInstanceRepository.findByProtocolInstanceIdAndActionIdAndStepStatus(any(), any(), any()))
                .thenAnswer(i -> stepRows.stream()
                        .filter(r -> r.getActionId().equals(i.getArgument(1))
                                && r.getStepStatus() == i.getArgument(2))
                        .toList());
        when(stepInstanceRepository.existsByProtocolInstanceIdAndActionId(any(), any()))
                .thenAnswer(i -> stepRows.stream().anyMatch(r -> r.getActionId().equals(i.getArgument(1))));
        when(stepInstanceRepository.findByStepGroupInstanceId(any()))
                .thenAnswer(i -> stepRows.stream().filter(r -> inOccurrence(r, i.getArgument(0))).toList());
        when(stepInstanceRepository.existsByStepGroupInstanceIdAndActionId(any(), any()))
                .thenAnswer(i -> stepRows.stream()
                        .anyMatch(r -> inOccurrence(r, i.getArgument(0)) && r.getActionId().equals(i.getArgument(1))));
    }

    private static boolean inOccurrence(StepInstance row, UUID occurrenceId) {
        return row.getStepGroupInstance() != null && occurrenceId.equals(row.getStepGroupInstance().getId());
    }

    private void stubGroupRows() {
        when(groupInstanceRepository.save(any(StepGroupInstance.class))).thenAnswer(i -> {
            StepGroupInstance row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                groupRows.add(row);
            }
            return row;
        });
        when(groupInstanceRepository.findByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIsNullAndCycleIndex(
                any(), any(), anyInt()))
                .thenAnswer(i -> groupRows.stream()
                        .filter(g -> g.getGroupActionId().equals(i.getArgument(1))
                                && g.getParentGroupInstance() == null
                                && g.getCycleIndex() == (int) i.getArgument(2))
                        .findFirst());
        when(groupInstanceRepository.findByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIdAndCycleIndex(
                any(), any(), any(), anyInt()))
                .thenAnswer(i -> groupRows.stream()
                        .filter(g -> g.getGroupActionId().equals(i.getArgument(1))
                                && g.getParentGroupInstance() != null
                                && g.getParentGroupInstance().getId().equals(i.getArgument(2))
                                && g.getCycleIndex() == (int) i.getArgument(3))
                        .findFirst());
        when(groupInstanceRepository.findByParentGroupInstanceId(any()))
                .thenAnswer(i -> groupRows.stream()
                        .filter(g -> g.getParentGroupInstance() != null
                                && g.getParentGroupInstance().getId().equals(i.getArgument(0)))
                        .toList());
        when(groupInstanceRepository.findByProtocolInstanceIdAndGroupActionId(any(), any()))
                .thenAnswer(i -> groupRows.stream()
                        .filter(g -> g.getGroupActionId().equals(i.getArgument(1)))
                        .toList());
    }

    // ── Protocols ──

    private static PlanDefinitionActionComponent step(PlanDefinitionActionComponent action, String id,
                                                      String requiredBehavior) {
        action.setId(id);
        action.getType().addCoding(new Coding()
                .setSystem("http://openphc.org/fhir/CodeSystem/action-type")
                .setCode("step"));
        action.setRequiredBehavior(ActionRequiredBehavior.fromCode(requiredBehavior));
        return action;
    }

    private static PlanDefinitionActionComponent group(PlanDefinitionActionComponent action, String id,
                                                       String requiredBehavior) {
        step(action, id, requiredBehavior).setGroupingBehavior(ActionGroupingBehavior.LOGICALGROUP);
        return action;
    }

    private static void afterStart(PlanDefinitionActionComponent action, String prerequisiteId, int days) {
        action.addRelatedAction()
                .setActionId(prerequisiteId)
                .setRelationship(ActionRelationshipType.AFTERSTART)
                .setOffset(new Duration().setValue(days).setUnit("d"));
    }

    private StepGroupInstance occurrence(String groupActionId, StepGroupInstance parent, int cycleIndex,
                                         OffsetDateTime openedAt) {
        return groupInstanceRepository.save(StepGroupInstance.builder()
                .protocolInstance(enrollment).parentGroupInstance(parent).groupActionId(groupActionId)
                .cycleIndex(cycleIndex).openedAt(openedAt).build());
    }

    private static void after(PlanDefinitionActionComponent action, String prerequisiteId, int days) {
        action.addRelatedAction()
                .setActionId(prerequisiteId)
                .setRelationship(ActionRelationshipType.AFTEREND)
                .setOffset(new Duration().setValue(days).setUnit("d"));
    }

    private void use(PlanDefinition pd) {
        PlanDefinitionParser parser = new PlanDefinitionParser(fhirContext);
        parser.validateGroups(pd);
        List<PlanDefinitionParser.StepMetadata> steps = parser.extractSteps(pd);
        List<PlanDefinitionParser.GroupMetadata> groups = parser.extractGroups(pd);
        protocol = new ParsedProtocolCache.ParsedProtocol(
                steps, PlanDefinitionParser.buildDependencyGraph(steps, groups), groups);
    }

    /** What {@code MatcherEngine.processMatch} does with a matched event, at clinical time {@code at}. */
    private StepInstance record(String actionId, OffsetDateTime at) {
        StepInstance row = service.findActionableStep(enrollment.getId(), actionId, at);
        if (row == null) {
            PlanDefinitionParser.StepMetadata metadata = protocol.step(actionId);
            row = metadata.groupId() != null
                    ? service.createInitialGroupedStep(enrollment, metadata, at, at)
                    : service.createStep(enrollment, actionId, 0, at, null, metadata.requiredBehavior());
        }
        service.completeStep(row, UUID.randomUUID(), "test", at);
        return row;
    }

    private List<StepInstance> rowsFor(String actionId) {
        return stepRows.stream().filter(r -> r.getActionId().equals(actionId)).toList();
    }

    private Optional<StepGroupInstance> occurrenceOf(String groupActionId) {
        return groupRows.stream().filter(g -> g.getGroupActionId().equals(groupActionId)).findFirst();
    }

    private static UUID occurrenceIdOf(StepInstance row) {
        return row.getStepGroupInstance() == null ? null : row.getStepGroupInstance().getId();
    }

    // ── Scenarios ──

    @Nested
    class QuarterlyReview {

        /** A mandatory "review" group: vitals first, an HbA1c draw 3 days after vitals, an optional consultation. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent review = group(pd.addAction(), "review", "must");
            step(review.addAction(), "vitals", "must");
            after(step(review.addAction(), "hba1c", "must"), "vitals", 3);
            step(review.addAction(), "consult", "could");
            use(pd);
        }

        @Test
        void theFirstEventOpensTheGroup_andItsStepJoinsTheOccurrence() {
            StepInstance vitals = record("vitals", T0);

            StepGroupInstance review = occurrenceOf("review").orElseThrow();
            assertEquals(review.getId(), occurrenceIdOf(vitals));
            assertEquals(T0, review.getOpenedAt());
            assertNull(review.getDueDate(), "a top-level group with no predecessor has no planned due date");
            assertEquals(T0, vitals.getDueDate(), "the matched step is due when the event made it, as outside a group");
            assertEquals(1, rowsFor("vitals").size(), "opening the group must not create the matched step twice");
        }

        @Test
        void aLaterMemberIsCreatedInTheSameOccurrence_withItsOwnOffset() {
            record("vitals", T0);

            StepInstance hba1c = rowsFor("hba1c").get(0);
            assertEquals(T0.plusDays(3), hba1c.getDueDate());
            assertEquals(occurrenceOf("review").orElseThrow().getId(), occurrenceIdOf(hba1c));
        }

        @Test
        void theGroupIsNeverRecordedComplete_evenWithEveryMandatoryStepDone() {
            record("vitals", T0);
            record("hba1c", T0.plusDays(2));

            StepGroupInstance review = occurrenceOf("review").orElseThrow();
            assertNull(review.getCompletedAt());
            assertNull(review.getClosedAt());
        }

        @Test
        void theGroupIsLockedForEveryDecision() {
            record("vitals", T0);

            verify(protocolInstanceRepository, atLeastOnce()).findByIdForUpdate(enrollment.getId());
        }
    }

    @Nested
    class AncVisit {

        /** Registration, then visit 2 six weeks later: BP and weight required, counselling optional. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "registration", "must");
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2", "must");
            after(visit, "registration", 42);
            step(visit.addAction(), "bp", "must");
            step(visit.addAction(), "weight", "must");
            step(visit.addAction(), "counselling", "could");
            PlanDefinitionActionComponent nutrition = group(visit.addAction(), "nutrition", "could");
            step(nutrition.addAction(), "diet", "must");
            PlanDefinitionActionComponent followUp = group(pd.addAction(), "follow-up", "could");
            after(followUp, "registration", 7);
            step(followUp.addAction(), "call", "must");
            use(pd);
        }

        @Test
        void aGroupFollowingAStepOpensWhenTheStepCompletes_withItsPlannedDueDate() {
            record("registration", T0);

            StepGroupInstance visit = occurrenceOf("visit-2").orElseThrow();
            assertEquals(T0.plusDays(42), visit.getDueDate());
            assertEquals(List.of(T0.plusDays(42)), rowsFor("bp").stream().map(StepInstance::getDueDate).toList());
            assertEquals(1, rowsFor("weight").size());
            assertTrue(rowsFor("counselling").isEmpty(), "optional steps are never created in advance");
            assertTrue(occurrenceOf("nutrition").isEmpty(), "optional groups are never opened in advance");
            assertTrue(occurrenceOf("follow-up").isEmpty(), "nor when they follow a step that completed");
        }

        @Test
        void aVisitOpenedByOneOfItsStepsIsNotOpenedAgain_whenItsPredecessorCompletes() {
            record("bp", T0);
            assertNull(occurrenceOf("visit-2").orElseThrow().getDueDate(), "registration hasn't happened yet");

            record("registration", T0.plusDays(1));

            assertEquals(1, groupRows.stream().filter(g -> g.getGroupActionId().equals("visit-2")).count());
        }

        @Test
        void anOptionalGroupOpensWhenOneOfItsStepsIsMatched_insideItsParent() {
            record("registration", T0);
            record("diet", T0.plusDays(39));

            StepGroupInstance nutrition = occurrenceOf("nutrition").orElseThrow();
            assertEquals(occurrenceOf("visit-2").orElseThrow().getId(), nutrition.getParentGroupInstance().getId());
            assertEquals(nutrition.getId(), occurrenceIdOf(rowsFor("diet").get(0)));
        }

        @Test
        void anEventForAStepAlreadyCreatedByItsGroupCompletesThatRow() {
            record("registration", T0);
            StepInstance created = rowsFor("bp").get(0);

            StepInstance completed = record("bp", T0.plusDays(41));

            assertSame(created, completed);
            assertEquals(1, rowsFor("bp").size());
            assertEquals(StepStatus.COMPLETED, created.getStepStatus());
        }
    }

    @Nested
    class TopLevelVisit {

        /** A top-level visit with no predecessor: BP and weight, both mandatory, neither waiting on the other. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit", "must");
            step(visit.addAction(), "bp", "must");
            step(visit.addAction(), "weight", "must");
            use(pd);
        }

        @Test
        void aStepCreatedWithAGroupThatHasNoDueDate_hasNoneAndNoDeadline() {
            record("bp", T0);

            StepInstance weight = rowsFor("weight").get(0);
            assertNull(occurrenceOf("visit").orElseThrow().getDueDate(), "a top-level group with no predecessor has no planned due date");
            assertNull(weight.getDueDate());
            assertEquals(StepStatus.NOT_STARTED, weight.getStepStatus());
            verify(slaScheduleService).schedule(weight, null, null);
        }

        @Test
        void anEventForAStepThatAnotherEventsOpeningCreated_getsThatRow() {
            record("bp", T0);
            StepInstance created = rowsFor("weight").get(0);

            assertSame(created, service.createInitialGroupedStep(enrollment, protocol.step("weight"), T0, T0));
            assertEquals(1, rowsFor("weight").size());
        }

        @Test
        void aSecondEventForACompletedStep_addsAFurtherCompletedRowInTheSameOccurrence() {
            record("bp", T0);
            record("bp", T0.plusMinutes(30));

            List<StepInstance> bps = rowsFor("bp");
            UUID visit = occurrenceOf("visit").orElseThrow().getId();
            assertEquals(2, bps.size());
            assertTrue(bps.stream().allMatch(r -> r.getStepStatus() == StepStatus.COMPLETED));
            assertTrue(bps.stream().allMatch(r -> visit.equals(occurrenceIdOf(r))));
            assertEquals(1, groupRows.size(), "no second occurrence is opened");
            assertEquals(1, rowsFor("weight").size());
        }
    }

    @Nested
    class PatientJourney {

        /**
         * A journey: check-in, then a nested consultation of history followed by examination. The
         * consultation follows check-in.
         */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = group(pd.addAction(), "journey", "must");
            step(journey.addAction(), "check-in", "must");
            PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation", "must");
            after(consultation, "check-in", 0);
            step(consultation.addAction(), "history", "must");
            after(step(consultation.addAction(), "examination", "must"), "history", 0);
            use(pd);
        }

        @Test
        void aNestedGroupOpensInsideItsParentsOccurrence() {
            record("check-in", T0);

            StepGroupInstance journey = occurrenceOf("journey").orElseThrow();
            StepGroupInstance consultation = occurrenceOf("consultation").orElseThrow();
            assertEquals(journey.getId(), consultation.getParentGroupInstance().getId());
            assertEquals(T0, consultation.getDueDate());
            assertEquals(consultation.getId(), occurrenceIdOf(rowsFor("history").get(0)));
            assertTrue(rowsFor("examination").isEmpty(), "examination waits for history");
        }

        @Test
        void anEventGoesToTheLatestInnerCycle_underTheLatestOuterCycle() {
            StepGroupInstance firstVisit = occurrence("journey", null, 0, T0);
            StepGroupInstance secondVisit = occurrence("journey", null, 1, T0.plusDays(14));
            StepGroupInstance secondInFirstVisit = occurrence("consultation", firstVisit, 1, T0.plusHours(5));
            StepGroupInstance firstInSecondVisit = occurrence("consultation", secondVisit, 0, T0.plusDays(14));
            service.createStep(enrollment, "history", 0, T0.plusHours(5), null, "must", secondInFirstVisit);
            StepInstance latest = service.createStep(
                    enrollment, "history", 0, T0.plusDays(14), null, "must", firstInSecondVisit);

            assertSame(latest, service.findActionableStep(enrollment.getId(), "history", T0.plusHours(5)));
        }

        @Test
        void nestedGroupsAreNeverRecordedComplete() {
            record("check-in", T0);
            record("history", T0.plusMinutes(20));
            record("examination", T0.plusMinutes(35));

            assertNull(occurrenceOf("consultation").orElseThrow().getCompletedAt());
            assertNull(occurrenceOf("journey").orElseThrow().getCompletedAt());
        }
    }

    @Nested
    class RowChoice {

        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "vitals", "must");
            use(pd);
        }

        @Test
        void anEventCompletesTheOpenRowWhoseDueDateIsNearestItsClinicalTime() {
            StepInstance january = service.createStep(enrollment, "vitals", 0, T0, null, "must");
            StepInstance april = service.createStep(enrollment, "vitals", 1, T0.plusMonths(3), null, "must");

            assertSame(april, service.findActionableStep(enrollment.getId(), "vitals", T0.plusMonths(3).plusDays(2)));
            assertSame(january, service.findActionableStep(enrollment.getId(), "vitals", T0.plusDays(10)));
        }

        @Test
        void twoRowsWithNoDueDate_theOlderComesFirst() {
            StepInstance older = service.createStep(enrollment, "vitals", 0, null, null, "must");
            service.createStep(enrollment, "vitals", 1, null, null, "must");

            assertSame(older, service.findActionableStep(enrollment.getId(), "vitals", T0));
        }

        @Test
        void aRowWithNoDueDateComesAfterDatedOnes() {
            StepInstance undated = service.createStep(enrollment, "vitals", 0, null, null, "must");
            StepInstance dated = service.createStep(enrollment, "vitals", 1, T0, null, "must");

            assertSame(dated, service.findActionableStep(enrollment.getId(), "vitals", T0.plusYears(1)));
            assertNotNull(undated.getId());
        }
    }

    @Nested
    class AnotherCycle {

        private StepGroupInstance earlier;
        private StepGroupInstance current;

        /** A visit: prescription follows examination. Two cycles exist. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit", "must");
            step(visit.addAction(), "examination", "must");
            after(step(visit.addAction(), "prescription", "must"), "examination", 0);
            use(pd);

            // Phase 1 opens one cycle per group, so the second is built by hand.
            earlier = openCycle(0, T0);
            current = openCycle(1, T0.plusDays(14));
        }

        private StepGroupInstance openCycle(int cycleIndex, OffsetDateTime openedAt) {
            return groupInstanceRepository.save(StepGroupInstance.builder()
                    .protocolInstance(enrollment).groupActionId("visit")
                    .cycleIndex(cycleIndex).openedAt(openedAt).build());
        }

        @Test
        void anExaminationLeftUndoneInAnEarlierCycleRaisesNoOrderViolation() {
            service.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            StepInstance examination = service.createStep(
                    enrollment, "examination", 0, T0.plusDays(14), null, "must", current);
            service.completeStep(examination, UUID.randomUUID(), "test", T0.plusDays(14));

            StepInstance prescription = rowsFor("prescription").get(0);
            assertEquals(current.getId(), occurrenceIdOf(prescription));
            service.completeStep(prescription, UUID.randomUUID(), "test", T0.plusDays(14).plusMinutes(30));

            verify(deviationRecorder, never()).recordDeviation(any(), any(), any());
        }

        @Test
        void anEventGoesToTheLatestCycle_evenWhenAnEarlierCycleRowIsNearerItsClinicalTime() {
            service.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            StepInstance latest = service.createStep(
                    enrollment, "examination", 0, T0.plusDays(14), null, "must", current);

            assertSame(latest, service.findActionableStep(enrollment.getId(), "examination", T0.plusDays(1)));
        }

        @Test
        void anEarlierCycleRowIsNeverCompletedByALaterEvent() {
            service.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            StepInstance latest = service.createStep(
                    enrollment, "examination", 0, T0.plusDays(14), null, "must", current);
            service.completeStep(latest, UUID.randomUUID(), "test", T0.plusDays(14));

            assertNull(service.findActionableStep(enrollment.getId(), "examination", T0.plusDays(1)));
        }

        @Test
        void aPredecessorMissingFromThisCycleIsBackfilledIntoIt_evenThoughAnEarlierCycleHasOne() {
            StepInstance done = service.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            service.completeStep(done, UUID.randomUUID(), "test", T0);
            int examinationsBefore = rowsFor("examination").size();

            StepInstance prescription = service.createStep(
                    enrollment, "prescription", 0, T0.plusDays(14), null, "must", current);
            service.completeStep(prescription, UUID.randomUUID(), "test", T0.plusDays(14));

            List<StepInstance> examinations = rowsFor("examination");
            assertEquals(examinationsBefore + 1, examinations.size());
            assertEquals(current.getId(), occurrenceIdOf(examinations.get(examinations.size() - 1)));
        }
    }

    @Nested
    class ClinicVisit {

        /** A visit: intake, and a measurements group holding BP; nothing waits on anything. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit", "must");
            step(visit.addAction(), "intake", "must");
            PlanDefinitionActionComponent measurements = group(visit.addAction(), "measurements", "must");
            step(measurements.addAction(), "bp", "must");
            use(pd);
        }

        @Test
        void aNestedGroupWithNoPredecessorOpensWithItsParent_andCreatesItsFirstSteps() {
            record("intake", T0);

            StepGroupInstance visit = occurrenceOf("visit").orElseThrow();
            StepGroupInstance measurements = occurrenceOf("measurements").orElseThrow();
            assertEquals(visit.getId(), measurements.getParentGroupInstance().getId());
            assertEquals(measurements.getId(), occurrenceIdOf(rowsFor("bp").get(0)));
        }
    }

    @Nested
    class TwoPrerequisites {

        /** A review group due 10 days after the assessment starts, and 5 days after the labs end. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "assessment", "must");
            step(pd.addAction(), "labs", "must");
            PlanDefinitionActionComponent review = group(pd.addAction(), "review", "must");
            afterStart(review, "assessment", 10);
            after(review, "labs", 5);
            step(review.addAction(), "summary", "must");
            use(pd);
        }

        @Test
        void aGroupWaitsWhileAnotherPrerequisiteIsInFlight_thenTakesTheLatestDueDate() {
            service.createStep(enrollment, "labs", 0, T0.plusDays(2), null, "must");
            record("assessment", T0);
            assertTrue(occurrenceOf("review").isEmpty(), "the labs are still outstanding");

            record("labs", T0.plusDays(3));

            assertEquals(T0.plusDays(10), occurrenceOf("review").orElseThrow().getDueDate(),
                    "10 days after the assessment started is later than 5 days after the labs ended");
        }
    }

    @Nested
    class StepsAcrossAGroupBoundary {

        /** Registration; a visit whose BP is due 7 days after registration; a treatment that follows BP. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "registration", "must");
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit", "must");
            after(step(visit.addAction(), "bp", "must"), "registration", 7);
            step(visit.addAction(), "weight", "must");
            after(step(pd.addAction(), "treatment", "must"), "bp", 0);
            use(pd);
        }

        @Test
        void aStepOutsideTheGroupCompleting_opensTheGroupForTheStepThatFollowsIt() {
            record("registration", T0);

            StepGroupInstance visit = occurrenceOf("visit").orElseThrow();
            assertEquals(1, rowsFor("bp").size());
            assertEquals(visit.getId(), occurrenceIdOf(rowsFor("bp").get(0)));
            assertEquals(visit.getId(), occurrenceIdOf(rowsFor("weight").get(0)));
        }

        @Test
        void aMissingPredecessorInsideAGroup_isBackfilledIntoTheGroupItOpens() {
            record("treatment", T0);

            StepGroupInstance visit = occurrenceOf("visit").orElseThrow();
            assertEquals(1, rowsFor("bp").size());
            assertEquals(visit.getId(), occurrenceIdOf(rowsFor("bp").get(0)));
        }
    }

    @Test
    void aProtocolWithoutGroupsIsNeverLocked() {
        PlanDefinition pd = new PlanDefinition();
        step(pd.addAction(), "vitals", "must");
        use(pd);

        record("vitals", T0);

        verify(protocolInstanceRepository, never()).findByIdForUpdate(any());
        assertTrue(groupRows.isEmpty());
        assertTrue(stepRows.stream().allMatch(r -> r.getStepGroupInstance() == null));
    }
}
