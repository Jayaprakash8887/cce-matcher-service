package org.openphc.cce.matcher.service;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.CodeType;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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

    // UTC+2, so a day boundary in it is not midnight UTC.
    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

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
        StepGroupService groupService = new StepGroupService(groupInstanceRepository, protocolInstanceRepository,
                KIGALI);
        service = new StepInstanceService(stepInstanceRepository, parsedProtocolCache, deviationRecorder,
                intelligenceActionEvaluator, stateTransitionHistoryWriter, slaScheduleService,
                slaThresholdReader, groupService);

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

    private static PlanDefinitionActionComponent repeatingBy(PlanDefinitionActionComponent group, String boundary) {
        group.addExtension("http://openphc.org/fhir/StructureDefinition/cycle-boundary", new CodeType(boundary));
        return group;
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
        StepInstance row = rowFor(actionId, at);
        service.completeStep(row, UUID.randomUUID(), "test", at);
        return row;
    }

    /** The row {@code MatcherEngine} picks for an event at clinical time {@code at}, created if need be. */
    private StepInstance rowFor(String actionId, OffsetDateTime at) {
        PlanDefinitionParser.StepMetadata metadata = protocol.step(actionId);
        if (metadata.groupId() != null) {
            return service.findOrCreateGroupedStep(enrollment, metadata, at, at);
        }
        StepInstance row = service.findActionableStep(enrollment.getId(), actionId, at);
        return row != null ? row : service.createStep(enrollment, actionId, 0, at, null, metadata.requiredBehavior());
    }

    private List<StepInstance> rowsFor(String actionId) {
        return stepRows.stream().filter(r -> r.getActionId().equals(actionId)).toList();
    }

    private Optional<StepGroupInstance> occurrenceOf(String groupActionId) {
        return groupRows.stream().filter(g -> g.getGroupActionId().equals(groupActionId)).findFirst();
    }

    /** A group's cycles within one parent occurrence (null for top-level), in cycle order. */
    private List<StepGroupInstance> cyclesOf(String groupActionId, StepGroupInstance parent) {
        return groupRows.stream()
                .filter(g -> g.getGroupActionId().equals(groupActionId))
                .filter(g -> parent == null
                        ? g.getParentGroupInstance() == null
                        : g.getParentGroupInstance() != null && g.getParentGroupInstance().getId().equals(parent.getId()))
                .sorted(Comparator.comparingInt(StepGroupInstance::getCycleIndex))
                .toList();
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
        void aStepRecordedBeforeWhatItsGroupFollows_backfillsIt() {
            record("bp", T0);

            List<StepInstance> registrations = rowsFor("registration");
            assertEquals(1, registrations.size(), "visit 2 follows registration, so BP does too");
            assertEquals(StepStatus.NOT_STARTED, registrations.get(0).getStepStatus());
            assertEquals(T0, registrations.get(0).getDueDate(), "due when the BP showed it was missing");
            assertNull(occurrenceIdOf(registrations.get(0)), "registration is outside every group");
        }

        @Test
        void soDoesAStepInANestedGroup() {
            record("diet", T0);

            assertEquals(1, rowsFor("registration").size());
        }

        @Test
        void theRegistrationEventLater_completesTheBackfilledRow() {
            record("bp", T0);
            StepInstance backfilled = rowsFor("registration").get(0);

            StepInstance completed = record("registration", T0.plusDays(1));

            assertSame(backfilled, completed);
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

            assertSame(created, rowFor("weight", T0));
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

            assertSame(latest, rowFor("history", T0.plusHours(5)));
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
    class RwandaJourney {

        private static final OffsetDateTime DAY_1 = T0;
        private static final OffsetDateTime DAY_15 = T0.plusDays(14);

        /**
         * A journey repeating by calendar day: check-in; a Consultation repeating by entry step, after
         * check-in, of history then examination; a prescription after the examination.
         */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = repeatingBy(group(pd.addAction(), "journey", "must"), "calendar-day");
            step(journey.addAction(), "check-in", "must");
            PlanDefinitionActionComponent consultation =
                    repeatingBy(group(journey.addAction(), "consultation", "must"), "entry-step");
            after(consultation, "check-in", 0);
            step(consultation.addAction(), "history", "must");
            after(step(consultation.addAction(), "examination", "must"), "history", 0);
            after(step(journey.addAction(), "prescription", "must"), "examination", 0);
            use(pd);
        }

        private StepGroupInstance visit(int cycleIndex) {
            return cyclesOf("journey", null).get(cycleIndex);
        }

        private StepGroupInstance consultation(StepGroupInstance visit, int cycleIndex) {
            return cyclesOf("consultation", visit).get(cycleIndex);
        }

        @Test
        void eachDayThePatientComesIsANewVisit_andThatDaysEventsGoToIt() {
            record("check-in", DAY_1);
            record("history", DAY_1.plusMinutes(20));
            record("check-in", DAY_15);
            record("history", DAY_15.plusMinutes(15));

            assertEquals(2, cyclesOf("journey", null).size());
            StepGroupInstance second = visit(1);
            assertEquals(DAY_15, second.getOpenedAt());
            assertNull(second.getDueDate(), "a visit opened because the patient came back has no planned due date");
            assertEquals(DAY_15, consultation(second, 0).getDueDate(),
                    "the second visit's Consultation counts from that visit's check-in, not the first's");
            assertEquals(List.of(consultation(visit(0), 0).getId(), consultation(second, 0).getId()),
                    rowsFor("history").stream().map(StepGroupLifecycleTest::occurrenceIdOf).toList());
        }

        @Test
        void anEventOnALaterDayOpensANewVisit_ratherThanCompletingThePreviousVisitsOpenRow() {
            record("check-in", DAY_1);
            StepInstance undone = rowsFor("history").get(0);

            StepInstance history = record("history", DAY_15);

            assertNotSame(undone, history);
            assertEquals(StepStatus.NOT_STARTED, undone.getStepStatus());
            assertEquals(consultation(visit(1), 0).getId(), occurrenceIdOf(history));
            StepInstance checkIn = rowsFor("check-in").get(1);
            assertEquals(visit(1).getId(), occurrenceIdOf(checkIn), "the new visit's check-in is awaited");
            assertNull(checkIn.getDueDate(), "with no due date, so it is never judged");
        }

        @Test
        void aLateEventForAnEarlierDay_goesToTheLatestVisit() {
            record("check-in", DAY_1);
            record("check-in", DAY_15);

            StepInstance history = record("history", DAY_1.plusDays(7));

            assertEquals(2, cyclesOf("journey", null).size(), "no visit is opened for an earlier day");
            assertEquals(consultation(visit(1), 0).getId(), occurrenceIdOf(history));
            assertEquals(StepStatus.NOT_STARTED, rowsFor("history").get(0).getStepStatus());
        }

        @Test
        void visitsAreDaysInTheDeploymentsTimeZone_twoTimesOnOneUtcDayCanBeTwoVisits() {
            // 23:00 and 00:30 in Kigali.
            record("check-in", OffsetDateTime.of(2026, 1, 10, 21, 0, 0, 0, ZoneOffset.UTC));
            record("history", OffsetDateTime.of(2026, 1, 10, 22, 30, 0, 0, ZoneOffset.UTC));

            assertEquals(2, cyclesOf("journey", null).size());
        }

        @Test
        void visitsAreDaysInTheDeploymentsTimeZone_twoUtcDaysCanBeOneVisit() {
            // 00:30 and 10:00 on 11 January in Kigali.
            record("check-in", OffsetDateTime.of(2026, 1, 10, 22, 30, 0, 0, ZoneOffset.UTC));
            record("history", OffsetDateTime.of(2026, 1, 11, 8, 0, 0, 0, ZoneOffset.UTC));

            assertEquals(1, cyclesOf("journey", null).size());
        }

        @Test
        void aRepeatedHistoryStartsASecondConsultation_aRepeatedExaminationJoinsTheCurrentOne() {
            record("check-in", DAY_1);
            record("history", DAY_1.plusHours(1));
            record("examination", DAY_1.plusHours(1).plusMinutes(15));
            record("history", DAY_1.plusHours(6));
            StepInstance second = record("examination", DAY_1.plusHours(6).plusMinutes(10));
            StepInstance repeated = record("examination", DAY_1.plusHours(6).plusMinutes(20));

            assertEquals(1, cyclesOf("journey", null).size());
            List<StepGroupInstance> consultations = cyclesOf("consultation", visit(0));
            assertEquals(2, consultations.size());
            assertNull(consultations.get(1).getDueDate(), "a second Consultation has no planned due date");
            assertEquals(consultations.get(1).getId(), occurrenceIdOf(second));
            assertEquals(consultations.get(1).getId(), occurrenceIdOf(repeated), "a further row in the same Consultation");
            assertEquals(StepStatus.COMPLETED, repeated.getStepStatus());
        }

        @Test
        void anExaminationLeftUndoneInAnEarlierVisit_raisesNoOrderViolationInALaterOne() {
            record("check-in", DAY_1);
            record("history", DAY_1.plusMinutes(20));

            record("check-in", DAY_15);
            record("history", DAY_15.plusMinutes(10));
            record("examination", DAY_15.plusMinutes(20));
            record("prescription", DAY_15.plusMinutes(30));

            assertEquals(StepStatus.NOT_STARTED, rowsFor("examination").get(0).getStepStatus());
            verify(deviationRecorder, never()).recordDeviation(any(), any(), any());
        }

        @Test
        void aStepFollowingAStepInANestedGroup_isCreatedAgainInEachVisit() {
            for (OffsetDateTime day : List.of(DAY_1, DAY_15)) {
                record("check-in", day);
                record("history", day.plusMinutes(10));
                record("examination", day.plusMinutes(20));
            }

            assertEquals(List.of(visit(0).getId(), visit(1).getId()),
                    rowsFor("prescription").stream().map(StepGroupLifecycleTest::occurrenceIdOf).toList());
        }

        @Test
        void aBackfilledStepJoinsTheLatestVisit() {
            record("check-in", DAY_1);

            record("prescription", DAY_15);

            StepGroupInstance laterConsultation = consultation(visit(1), 0);
            List<StepInstance> examinations = rowsFor("examination");
            assertEquals(1, examinations.size());
            assertEquals(laterConsultation.getId(), occurrenceIdOf(examinations.get(0)));
            assertTrue(cyclesOf("consultation", visit(0)).stream().allMatch(c -> c.getCycleIndex() == 0));
        }
    }

    @Nested
    class TreatmentAfterAVisit {

        private static final OffsetDateTime DAY_1 = T0;
        private static final OffsetDateTime DAY_15 = T0.plusDays(14);

        /**
         * A journey repeating by calendar day: check-in, then examination, then prescription. A treatment
         * outside the journey follows the prescription, so it shares no group with it.
         */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = repeatingBy(group(pd.addAction(), "journey", "must"), "calendar-day");
            step(journey.addAction(), "check-in", "must");
            after(step(journey.addAction(), "examination", "must"), "check-in", 0);
            after(step(journey.addAction(), "prescription", "must"), "examination", 0);
            after(step(pd.addAction(), "treatment", "must"), "prescription", 0);
            use(pd);
        }

        private StepGroupInstance visit(int cycleIndex) {
            return cyclesOf("journey", null).get(cycleIndex);
        }

        @Test
        void aPrescriptionLeftUndoneInAnEarlierVisit_raisesNoOrderViolationForTheTreatment() {
            record("check-in", DAY_1);
            record("examination", DAY_1.plusMinutes(20));
            assertEquals(StepStatus.NOT_STARTED, rowsFor("prescription").get(0).getStepStatus());

            record("check-in", DAY_15);
            record("examination", DAY_15.plusMinutes(10));
            record("prescription", DAY_15.plusMinutes(20));
            record("treatment", DAY_15.plusMinutes(30));

            verify(deviationRecorder, never()).recordDeviation(any(), any(), any());
        }

        @Test
        void aPrescriptionMissingFromTheLatestVisit_isBackfilledIntoIt_thoughAnEarlierVisitHasOne() {
            record("check-in", DAY_1);
            record("examination", DAY_1.plusMinutes(10));

            record("check-in", DAY_15);
            record("treatment", DAY_15.plusMinutes(30));

            List<StepInstance> prescriptions = rowsFor("prescription");
            assertEquals(2, prescriptions.size());
            assertEquals(visit(1).getId(), occurrenceIdOf(prescriptions.get(1)));
            assertEquals(StepStatus.NOT_STARTED, prescriptions.get(1).getStepStatus());
        }
    }

    @Nested
    class JourneyAfterRegistration {

        /**
         * Registration; a journey repeating by calendar day that follows it, holding check-in, a consent
         * and a triage group, all due on registration; a discharge after check-in, outside the journey.
         */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "registration", "must");
            PlanDefinitionActionComponent journey = repeatingBy(group(pd.addAction(), "journey", "must"), "calendar-day");
            after(journey, "registration", 0);
            step(journey.addAction(), "check-in", "must");
            after(step(journey.addAction(), "consent", "must"), "registration", 0);
            PlanDefinitionActionComponent triage = group(journey.addAction(), "triage", "must");
            after(triage, "registration", 0);
            after(step(triage.addAction(), "vitals", "must"), "registration", 0);
            after(step(pd.addAction(), "discharge", "must"), "check-in", 0);
            use(pd);
        }

        @Test
        void registrationOpensNoVisit_theFirstVisitOpensOnTheDayThePatientComes() {
            record("registration", T0);

            assertTrue(groupRows.isEmpty(), "only an event for one of its steps opens a calendar-day cycle");
            assertTrue(rowsFor("consent").isEmpty(), "nor does a step that follows registration");
            assertTrue(rowsFor("vitals").isEmpty(), "nor a step or group nested in it");

            record("check-in", T0.plusDays(3));

            StepGroupInstance visit = cyclesOf("journey", null).get(0);
            assertEquals(T0.plusDays(3), visit.getOpenedAt());
            assertEquals(T0, visit.getDueDate(), "the first visit takes its planned due date");
            assertEquals(visit.getId(), occurrenceIdOf(rowsFor("consent").get(0)));
        }

        @Test
        void aStepOutsideTheJourney_backfillsNothingIntoAJourneyWithNoVisit() {
            record("discharge", T0);

            assertTrue(groupRows.isEmpty());
            assertTrue(rowsFor("check-in").isEmpty());
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

            assertSame(latest, rowFor("examination", T0.plusDays(1)));
        }

        @Test
        void anEarlierCycleRowIsNeverCompletedByALaterEvent() {
            StepInstance undone = service.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            StepInstance latest = service.createStep(
                    enrollment, "examination", 0, T0.plusDays(14), null, "must", current);
            service.completeStep(latest, UUID.randomUUID(), "test", T0.plusDays(14));

            StepInstance row = rowFor("examination", T0.plusDays(1));

            assertNotSame(undone, row);
            assertEquals(current.getId(), occurrenceIdOf(row), "a further row in the latest cycle");
            assertEquals(StepStatus.NOT_STARTED, undone.getStepStatus());
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
