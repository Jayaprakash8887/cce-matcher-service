package org.openphc.cce.matcher.service;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Duration;
import org.hl7.fhir.r4.model.IdType;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Step groups end to end through {@link StepInstanceService} and {@link StepGroupService}: opening an
 * instance, attaching steps, progressing inside it, and choosing which row an event completes. Groups
 * are never recorded as complete or closed yet. The repositories are in memory; the protocols are parsed
 * by the real parser.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepGroupLifecycleTest {

    // In the past: completeStepInstance clamps a future clinical time to now.
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
    private StepCreationService stepCreationService;
    private ProtocolInstance enrollment;
    private ParsedProtocolCache.ParsedProtocol protocol;

    @BeforeAll
    static void initFhirContext() {
        fhirContext = FhirContext.forR4();
    }

    @BeforeEach
    void setUp() {
        stepCreationService = new StepCreationService(stepInstanceRepository, slaScheduleService,
                stateTransitionHistoryWriter);
        StepInstanceScope stepInstanceScope = new StepInstanceScope(groupInstanceRepository);
        StepGroupService groupService =
                new StepGroupService(groupInstanceRepository, stepCreationService, stepInstanceScope, KIGALI);
        service = new StepInstanceService(stepInstanceRepository, parsedProtocolCache, stateTransitionHistoryWriter,
                slaScheduleService, slaThresholdReader,
                new ProtocolInstanceService(protocolInstanceRepository, stateTransitionHistoryWriter),
                groupService, stepCreationService,
                new OrderViolationDetector(stepInstanceScope, deviationRecorder, intelligenceActionEvaluator),
                new ProgressiveInstantiationService(groupService, stepInstanceScope, stepCreationService),
                new MandatoryStepBackfillService(groupService, stepInstanceScope, stepCreationService));

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
        when(groupInstanceRepository.findLatestCycle(any(), any(), any()))
                .thenAnswer(i -> groupRows.stream()
                        .filter(g -> g.getGroupActionId().equals(i.getArgument(1)))
                        .filter(g -> Objects.equals(i.getArgument(2),
                                g.getParentGroupInstance() == null ? null : g.getParentGroupInstance().getId()))
                        .max(Comparator.comparingInt(StepGroupInstance::getCycleIndex)));
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

    private StepGroupInstance groupInstance(String groupActionId, StepGroupInstance parent, int cycleIndex,
                                         OffsetDateTime openedAt) {
        return groupInstanceRepository.save(StepGroupInstance.builder()
                .protocolInstance(enrollment).parentGroupInstance(parent).groupActionId(groupActionId)
                .cycleIndex(cycleIndex).openedAt(openedAt).build());
    }

    private static PlanDefinitionActionComponent repeatingBy(PlanDefinitionActionComponent group, String boundary) {
        group.addExtension("http://openphc.org/fhir/StructureDefinition/cycle-boundary", new CodeType(boundary));
        return group;
    }

    /** {@code group} names {@code stepId} as its entry step: the step whose repeat starts a new cycle. */
    private static PlanDefinitionActionComponent namingEntryStep(PlanDefinitionActionComponent group, String stepId) {
        group.addExtension("http://openphc.org/fhir/StructureDefinition/cycle-entry-step", new IdType(stepId));
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
        service.completeStepInstance(row, UUID.randomUUID(), "test", at);
        return row;
    }

    /** The row {@code MatcherEngine} picks for an event at clinical time {@code at}, created if need be. */
    private StepInstance rowFor(String actionId, OffsetDateTime at) {
        return service.stepInstanceForEvent(enrollment, actionId, at, at);
    }

    private List<StepInstance> rowsFor(String actionId) {
        return stepRows.stream().filter(r -> r.getActionId().equals(actionId)).toList();
    }

    private Optional<StepGroupInstance> groupInstanceOf(String groupActionId) {
        return groupRows.stream().filter(g -> g.getGroupActionId().equals(groupActionId)).findFirst();
    }

    /** A group's cycles within one parent instance (null for top-level), in cycle order. */
    private List<StepGroupInstance> cyclesOf(String groupActionId, StepGroupInstance parent) {
        return groupRows.stream()
                .filter(g -> g.getGroupActionId().equals(groupActionId))
                .filter(g -> parent == null
                        ? g.getParentGroupInstance() == null
                        : g.getParentGroupInstance() != null && g.getParentGroupInstance().getId().equals(parent.getId()))
                .sorted(Comparator.comparingInt(StepGroupInstance::getCycleIndex))
                .toList();
    }

    private static UUID groupInstanceIdOf(StepInstance row) {
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
        void theFirstEventOpensTheGroup_andItsStepJoinsTheGroupInstance() {
            StepInstance vitals = record("vitals", T0);

            StepGroupInstance review = groupInstanceOf("review").orElseThrow();
            assertEquals(review.getId(), groupInstanceIdOf(vitals));
            assertEquals(T0, review.getOpenedAt());
            assertNull(review.getDueDate(), "a top-level group with no predecessor has no planned due date");
            assertEquals(T0, vitals.getDueDate(), "the matched step is due when the event made it, as outside a group");
            assertEquals(1, rowsFor("vitals").size(), "opening the group must not create the matched step twice");
        }

        @Test
        void aLaterMemberIsCreatedInTheSameGroupInstance_withItsOwnOffset() {
            record("vitals", T0);

            StepInstance hba1c = rowsFor("hba1c").get(0);
            assertEquals(T0.plusDays(3), hba1c.getDueDate());
            assertEquals(groupInstanceOf("review").orElseThrow().getId(), groupInstanceIdOf(hba1c));
        }

        @Test
        void theGroupIsNeverRecordedComplete_evenWithEveryMandatoryStepDone() {
            record("vitals", T0);
            record("hba1c", T0.plusDays(2));

            StepGroupInstance review = groupInstanceOf("review").orElseThrow();
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

            StepGroupInstance visit = groupInstanceOf("visit-2").orElseThrow();
            assertEquals(T0.plusDays(42), visit.getDueDate());
            assertEquals(List.of(T0.plusDays(42)), rowsFor("bp").stream().map(StepInstance::getDueDate).toList());
            assertEquals(1, rowsFor("weight").size());
            assertTrue(rowsFor("counselling").isEmpty(), "optional steps are never created in advance");
            assertTrue(groupInstanceOf("nutrition").isEmpty(), "optional groups are never opened in advance");
            assertTrue(groupInstanceOf("follow-up").isEmpty(), "nor when they follow a step that completed");
        }

        @Test
        void aVisitOpenedByOneOfItsStepsIsNotOpenedAgain_whenItsPredecessorCompletes() {
            record("bp", T0);
            assertNull(groupInstanceOf("visit-2").orElseThrow().getDueDate(), "registration hasn't happened yet");

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
            assertNull(groupInstanceIdOf(registrations.get(0)), "registration is outside every group");
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

            StepGroupInstance nutrition = groupInstanceOf("nutrition").orElseThrow();
            assertEquals(groupInstanceOf("visit-2").orElseThrow().getId(), nutrition.getParentGroupInstance().getId());
            assertEquals(nutrition.getId(), groupInstanceIdOf(rowsFor("diet").get(0)));
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
            assertNull(groupInstanceOf("visit").orElseThrow().getDueDate(), "a top-level group with no predecessor has no planned due date");
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
        void aSecondEventForACompletedStep_addsAFurtherCompletedRowInTheSameGroupInstance() {
            record("bp", T0);
            record("bp", T0.plusMinutes(30));

            List<StepInstance> bps = rowsFor("bp");
            UUID visit = groupInstanceOf("visit").orElseThrow().getId();
            assertEquals(2, bps.size());
            assertTrue(bps.stream().allMatch(r -> r.getStepStatus() == StepStatus.COMPLETED));
            assertTrue(bps.stream().allMatch(r -> visit.equals(groupInstanceIdOf(r))));
            assertEquals(1, groupRows.size(), "no second group instance is opened");
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
        void aNestedGroupOpensInsideItsParentsGroupInstance() {
            record("check-in", T0);

            StepGroupInstance journey = groupInstanceOf("journey").orElseThrow();
            StepGroupInstance consultation = groupInstanceOf("consultation").orElseThrow();
            assertEquals(journey.getId(), consultation.getParentGroupInstance().getId());
            assertEquals(T0, consultation.getDueDate());
            assertEquals(consultation.getId(), groupInstanceIdOf(rowsFor("history").get(0)));
            assertTrue(rowsFor("examination").isEmpty(), "examination waits for history");
        }

        @Test
        void anEventGoesToTheLatestInnerCycle_underTheLatestOuterCycle() {
            StepGroupInstance firstVisit = groupInstance("journey", null, 0, T0);
            StepGroupInstance secondVisit = groupInstance("journey", null, 1, T0.plusDays(14));
            StepGroupInstance secondInFirstVisit = groupInstance("consultation", firstVisit, 1, T0.plusHours(5));
            StepGroupInstance firstInSecondVisit = groupInstance("consultation", secondVisit, 0, T0.plusDays(14));
            stepCreationService.createStep(enrollment, "history", 0, T0.plusHours(5), null, "must", secondInFirstVisit);
            StepInstance latest = stepCreationService.createStep(
                    enrollment, "history", 0, T0.plusDays(14), null, "must", firstInSecondVisit);

            assertSame(latest, rowFor("history", T0.plusHours(5)));
        }

        @Test
        void nestedGroupsAreNeverRecordedComplete() {
            record("check-in", T0);
            record("history", T0.plusMinutes(20));
            record("examination", T0.plusMinutes(35));

            assertNull(groupInstanceOf("consultation").orElseThrow().getCompletedAt());
            assertNull(groupInstanceOf("journey").orElseThrow().getCompletedAt());
        }
    }

    @Nested
    class RepeatingGroupInsideGroupsThatDoNot {

        /** Group 1 holding group 2 holding group 3, which repeats by calendar day and holds step 1. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent group1 = group(pd.addAction(), "group-1", "must");
            PlanDefinitionActionComponent group2 = group(group1.addAction(), "group-2", "must");
            PlanDefinitionActionComponent group3 = repeatingBy(group(group2.addAction(), "group-3", "must"), "calendar-day");
            step(group3.addAction(), "step-1", "must");
            use(pd);
        }

        @Test
        void aNewDayOpensANewCycleOfTheRepeatingGroupOnly_insideTheSameEnclosingInstances() {
            record("step-1", T0);
            record("step-1", T0.plusDays(1));

            List<StepGroupInstance> group1 = cyclesOf("group-1", null);
            assertEquals(1, group1.size());
            List<StepGroupInstance> group2 = cyclesOf("group-2", group1.get(0));
            assertEquals(1, group2.size());
            List<StepGroupInstance> group3 = cyclesOf("group-3", group2.get(0));
            assertEquals(2, group3.size());
            assertEquals(List.of(group3.get(0).getId(), group3.get(1).getId()),
                    rowsFor("step-1").stream().map(StepGroupLifecycleTest::groupInstanceIdOf).toList());
        }
    }

    @Nested
    class OuterGroupRepeatsByDay {

        /**
         * Group 1 repeating by calendar day, holding group 2, holding group 3 repeating by entry step with
         * step 1 as its entry step. A group inside a repeating group can only repeat by entry step.
         */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent group1 = repeatingBy(group(pd.addAction(), "group-1", "must"), "calendar-day");
            PlanDefinitionActionComponent group2 = group(group1.addAction(), "group-2", "must");
            PlanDefinitionActionComponent group3 = namingEntryStep(repeatingBy(group(group2.addAction(), "group-3", "must"), "entry-step"), "step-1");
            step(group3.addAction(), "step-1", "must");
            use(pd);
        }

        @Test
        void aRepeatSameDayStartsTheInnerGroupAgain_aNewDayStartsEveryLevelAgain() {
            record("step-1", T0);
            record("step-1", T0.plusHours(1));
            record("step-1", T0.plusDays(1));

            List<StepGroupInstance> group1 = cyclesOf("group-1", null);
            assertEquals(2, group1.size(), "one group 1 instance per day");

            List<StepGroupInstance> dayOneGroup2 = cyclesOf("group-2", group1.get(0));
            assertEquals(1, dayOneGroup2.size());
            assertEquals(2, cyclesOf("group-3", dayOneGroup2.get(0)).size(), "step 1 repeated on day 1");

            List<StepGroupInstance> dayTwoGroup2 = cyclesOf("group-2", group1.get(1));
            assertEquals(1, dayTwoGroup2.size());
            assertEquals(1, cyclesOf("group-3", dayTwoGroup2.get(0)).size(), "day 2 starts again at cycle 0");
        }
    }

    @Test
    void aGroupInsideARepeatingGroupCannotRepeatByCalendarDay() {
        PlanDefinition pd = new PlanDefinition();
        PlanDefinitionActionComponent group1 = repeatingBy(group(pd.addAction(), "group-1", "must"), "calendar-day");
        PlanDefinitionActionComponent group2 = repeatingBy(group(group1.addAction(), "group-2", "must"), "calendar-day");
        step(group2.addAction(), "step-1", "must");

        assertThrows(IllegalArgumentException.class, () -> use(pd));
    }

    @Nested
    class EntryStep {

        /** A Consultation repeating by entry step, of history and BP, naming history as its entry step. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent consultation =
                    namingEntryStep(repeatingBy(group(pd.addAction(), "consultation", "must"), "entry-step"), "history");
            step(consultation.addAction(), "history", "must");
            step(consultation.addAction(), "bp", "must");
            use(pd);
        }

        @Test
        void aRepeatedStepThatIsNotTheEntryStep_joinsTheCycle_aRepeatedEntryStepStartsTheNext() {
            record("history", T0);
            record("bp", T0.plusMinutes(10));
            record("bp", T0.plusMinutes(20));

            assertEquals(1, cyclesOf("consultation", null).size());
            assertEquals(2, rowsFor("bp").size());

            record("history", T0.plusHours(2));

            assertEquals(2, cyclesOf("consultation", null).size());
        }

        @Test
        void anotherStepFirst_opensCycle0_andTheEntryStepThatFollowsStartsCycle1() {
            record("bp", T0);

            assertEquals(1, cyclesOf("consultation", null).size());

            record("history", T0.plusMinutes(10));

            List<StepGroupInstance> cycles = cyclesOf("consultation", null);
            assertEquals(2, cycles.size());
            List<StepInstance> histories = rowsFor("history");
            assertEquals(1, histories.size(), "BP opening cycle 0 leaves history to its event");
            assertEquals(cycles.get(1).getId(), groupInstanceIdOf(histories.get(0)));
        }

        @Test
        void eachEntryStepStartsTheNextCycle() {
            record("history", T0);
            record("history", T0.plusMinutes(10));

            assertEquals(2, cyclesOf("consultation", null).size());
        }
    }

    @Nested
    class EntryStepFollowsAStepOutsideItsGroup {

        /** Group 1 of step 1 and group 2, repeating by entry step, whose entry step follows step 1 by 2 days. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent group1 = group(pd.addAction(), "group-1", "must");
            step(group1.addAction(), "step-1", "must");
            PlanDefinitionActionComponent group2 =
                    namingEntryStep(repeatingBy(group(group1.addAction(), "group-2", "must"), "entry-step"), "g2-step-1");
            after(step(group2.addAction(), "g2-step-1", "must"), "step-1", 2);
            use(pd);
        }

        @Test
        void stepOnesCompletion_opensCycle0_withTheEntryStepAwaiting_dueByItsOwnOffset() {
            record("step-1", T0);

            StepGroupInstance group1 = cyclesOf("group-1", null).get(0);
            assertEquals(1, cyclesOf("group-2", group1).size());
            StepInstance awaiting = rowsFor("g2-step-1").get(0);
            assertEquals(StepStatus.NOT_STARTED, awaiting.getStepStatus());
            assertEquals(T0.plusDays(2), awaiting.getDueDate());
            assertEquals(cyclesOf("group-2", group1).get(0).getId(), groupInstanceIdOf(awaiting));
        }

        @Test
        void theFirstEntryStepEvent_completesTheAwaitingOne_theNextStartsCycle1() {
            record("step-1", T0);
            StepInstance first = record("g2-step-1", T0.plusDays(1));

            StepGroupInstance group1 = cyclesOf("group-1", null).get(0);
            assertEquals(1, cyclesOf("group-2", group1).size());
            assertSame(rowsFor("g2-step-1").get(0), first);
            assertEquals(StepStatus.COMPLETED, first.getStepStatus());

            record("g2-step-1", T0.plusDays(3));

            assertEquals(2, cyclesOf("group-2", group1).size());
        }
    }

    @Nested
    class EntryStepParent {

        /** Group 1 repeating by entry step, holding its entry step intake and group 2, which holds step 1. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent group1 = namingEntryStep(repeatingBy(group(pd.addAction(), "group-1", "must"), "entry-step"), "intake");
            step(group1.addAction(), "intake", "must");
            PlanDefinitionActionComponent group2 = group(group1.addAction(), "group-2", "must");
            step(group2.addAction(), "step-1", "must");
            use(pd);
        }

        @Test
        void aRepeatedStepInsideANestedGroup_neverStartsTheParentsNextCycle_itsOwnEntryStepDoes() {
            record("intake", T0);
            record("step-1", T0.plusMinutes(10));
            record("step-1", T0.plusMinutes(20));

            assertEquals(1, cyclesOf("group-1", null).size());
            assertEquals(1, cyclesOf("group-2", cyclesOf("group-1", null).get(0)).size());

            record("intake", T0.plusHours(2));

            assertEquals(2, cyclesOf("group-1", null).size());
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
                    namingEntryStep(repeatingBy(group(journey.addAction(), "consultation", "must"), "entry-step"), "history");
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
                    rowsFor("history").stream().map(StepGroupLifecycleTest::groupInstanceIdOf).toList());
        }

        @Test
        void anEventOnALaterDayOpensANewVisit_ratherThanCompletingThePreviousVisitsOpenRow() {
            record("check-in", DAY_1);
            record("history", DAY_1.plusMinutes(10));
            StepInstance undone = rowsFor("examination").get(0);

            StepInstance examination = record("examination", DAY_15);

            assertNotSame(undone, examination);
            assertEquals(StepStatus.NOT_STARTED, undone.getStepStatus());
            assertEquals(consultation(visit(1), 0).getId(), groupInstanceIdOf(examination),
                    "an examination before any history that day still opens the visit's Consultation");
            StepInstance checkIn = rowsFor("check-in").get(1);
            assertEquals(visit(1).getId(), groupInstanceIdOf(checkIn), "the new visit's check-in is awaited");
            assertNull(checkIn.getDueDate(), "with no due date, so it is never judged");
        }

        @Test
        void aLateEventForAnEarlierDay_goesToTheLatestVisit() {
            record("check-in", DAY_1);
            record("check-in", DAY_15);

            StepInstance history = record("history", DAY_1.plusDays(7));

            assertEquals(2, cyclesOf("journey", null).size(), "no visit is opened for an earlier day");
            assertEquals(consultation(visit(1), 0).getId(), groupInstanceIdOf(history));
            assertTrue(cyclesOf("consultation", visit(0)).isEmpty(), "the earlier visit gets no Consultation");
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
            assertEquals(consultations.get(1).getId(), groupInstanceIdOf(second));
            assertEquals(consultations.get(1).getId(), groupInstanceIdOf(repeated), "a further row in the same Consultation");
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
                    rowsFor("prescription").stream().map(StepGroupLifecycleTest::groupInstanceIdOf).toList());
        }

        @Test
        void anExaminationBeforeHistory_backfillsHistory_whichTheHistoryThatFollowsCompletes() {
            record("check-in", DAY_1);
            record("history", DAY_1.plusMinutes(10));

            record("examination", DAY_15);
            StepInstance backfilled = rowsFor("history").get(1);
            assertEquals(StepStatus.NOT_STARTED, backfilled.getStepStatus());
            assertEquals(consultation(visit(1), 0).getId(), groupInstanceIdOf(backfilled));

            StepInstance history = record("history", DAY_15.plusMinutes(10));

            assertSame(backfilled, history);
            assertEquals(1, cyclesOf("consultation", visit(1)).size(), "no second Consultation");
        }

        @Test
        void checkInDoesNotStartAConsultation_onlyAnEventForOneOfItsStepsDoes() {
            record("check-in", DAY_1);

            assertTrue(cyclesOf("consultation", visit(0)).isEmpty());
            assertTrue(rowsFor("history").isEmpty(), "history is not awaited before the Consultation starts");

            record("history", DAY_1.plusMinutes(10));

            assertEquals(1, cyclesOf("consultation", visit(0)).size());
        }

        @Test
        void aStepThatWouldBeBackfilledIntoAConsultationNotYetStarted_waitsForTheConsultation() {
            record("check-in", DAY_1);

            record("prescription", DAY_15);

            assertTrue(cyclesOf("consultation", visit(1)).isEmpty());
            assertTrue(rowsFor("examination").isEmpty(), "examination is not backfilled while no Consultation has started");
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
            assertEquals(visit(1).getId(), groupInstanceIdOf(prescriptions.get(1)));
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
            assertEquals(visit.getId(), groupInstanceIdOf(rowsFor("consent").get(0)));
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
            StepInstance january = stepCreationService.createStep(enrollment, "vitals", 0, T0, null, "must");
            StepInstance april = stepCreationService.createStep(enrollment, "vitals", 1, T0.plusMonths(3), null, "must");

            assertSame(april, rowFor("vitals", T0.plusMonths(3).plusDays(2)));
            assertSame(january, rowFor("vitals", T0.plusDays(10)));
        }

        @Test
        void twoRowsWithNoDueDate_theOlderComesFirst() {
            StepInstance older = stepCreationService.createStep(enrollment, "vitals", 0, null, null, "must");
            stepCreationService.createStep(enrollment, "vitals", 1, null, null, "must");

            assertSame(older, rowFor("vitals", T0));
        }

        @Test
        void aRowWithNoDueDateComesAfterDatedOnes() {
            StepInstance undated = stepCreationService.createStep(enrollment, "vitals", 0, null, null, "must");
            StepInstance dated = stepCreationService.createStep(enrollment, "vitals", 1, T0, null, "must");

            assertSame(dated, rowFor("vitals", T0.plusYears(1)));
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
            stepCreationService.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            StepInstance examination = stepCreationService.createStep(
                    enrollment, "examination", 0, T0.plusDays(14), null, "must", current);
            service.completeStepInstance(examination, UUID.randomUUID(), "test", T0.plusDays(14));

            StepInstance prescription = rowsFor("prescription").get(0);
            assertEquals(current.getId(), groupInstanceIdOf(prescription));
            service.completeStepInstance(prescription, UUID.randomUUID(), "test", T0.plusDays(14).plusMinutes(30));

            verify(deviationRecorder, never()).recordDeviation(any(), any(), any());
        }

        @Test
        void anEventGoesToTheLatestCycle_evenWhenAnEarlierCycleRowIsNearerItsClinicalTime() {
            stepCreationService.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            StepInstance latest = stepCreationService.createStep(
                    enrollment, "examination", 0, T0.plusDays(14), null, "must", current);

            assertSame(latest, rowFor("examination", T0.plusDays(1)));
        }

        @Test
        void anEarlierCycleRowIsNeverCompletedByALaterEvent() {
            StepInstance undone = stepCreationService.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            StepInstance latest = stepCreationService.createStep(
                    enrollment, "examination", 0, T0.plusDays(14), null, "must", current);
            service.completeStepInstance(latest, UUID.randomUUID(), "test", T0.plusDays(14));

            StepInstance row = rowFor("examination", T0.plusDays(1));

            assertNotSame(undone, row);
            assertEquals(current.getId(), groupInstanceIdOf(row), "a further row in the latest cycle");
            assertEquals(StepStatus.NOT_STARTED, undone.getStepStatus());
        }

        @Test
        void aPredecessorMissingFromThisCycleIsBackfilledIntoIt_evenThoughAnEarlierCycleHasOne() {
            StepInstance done = stepCreationService.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            service.completeStepInstance(done, UUID.randomUUID(), "test", T0);
            int examinationsBefore = rowsFor("examination").size();

            StepInstance prescription = stepCreationService.createStep(
                    enrollment, "prescription", 0, T0.plusDays(14), null, "must", current);
            service.completeStepInstance(prescription, UUID.randomUUID(), "test", T0.plusDays(14));

            List<StepInstance> examinations = rowsFor("examination");
            assertEquals(examinationsBefore + 1, examinations.size());
            assertEquals(current.getId(), groupInstanceIdOf(examinations.get(examinations.size() - 1)));
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

            StepGroupInstance visit = groupInstanceOf("visit").orElseThrow();
            StepGroupInstance measurements = groupInstanceOf("measurements").orElseThrow();
            assertEquals(visit.getId(), measurements.getParentGroupInstance().getId());
            assertEquals(measurements.getId(), groupInstanceIdOf(rowsFor("bp").get(0)));
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
            stepCreationService.createStep(enrollment, "labs", 0, T0.plusDays(2), null, "must");
            record("assessment", T0);
            assertTrue(groupInstanceOf("review").isEmpty(), "the labs are still outstanding");

            record("labs", T0.plusDays(3));

            assertEquals(T0.plusDays(10), groupInstanceOf("review").orElseThrow().getDueDate(),
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

            StepGroupInstance visit = groupInstanceOf("visit").orElseThrow();
            assertEquals(1, rowsFor("bp").size());
            assertEquals(visit.getId(), groupInstanceIdOf(rowsFor("bp").get(0)));
            assertEquals(visit.getId(), groupInstanceIdOf(rowsFor("weight").get(0)));
        }

        @Test
        void aMissingPredecessorInsideAGroup_isBackfilledIntoTheGroupItOpens() {
            record("treatment", T0);

            StepGroupInstance visit = groupInstanceOf("visit").orElseThrow();
            assertEquals(1, rowsFor("bp").size());
            assertEquals(visit.getId(), groupInstanceIdOf(rowsFor("bp").get(0)));
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
