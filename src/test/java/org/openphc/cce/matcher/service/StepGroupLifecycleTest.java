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
    private EnrollmentLoader enrollmentLoader;
    private OrderViolationDetector orderViolationDetector;
    private ProgressiveInstantiationService progressiveInstantiationService;
    private MandatoryStepBackfillService mandatoryStepBackfillService;
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
        enrollmentLoader = new EnrollmentLoader(
                new ProtocolInstanceService(protocolInstanceRepository, stateTransitionHistoryWriter),
                stepInstanceRepository, parsedProtocolCache);
        service = new StepInstanceService(stepInstanceRepository, enrollmentLoader, stateTransitionHistoryWriter,
                slaScheduleService, groupService, stepCreationService);
        orderViolationDetector =
                new OrderViolationDetector(stepInstanceScope, deviationRecorder, intelligenceActionEvaluator);
        progressiveInstantiationService = new ProgressiveInstantiationService(groupService, stepInstanceScope,
                stepCreationService, slaThresholdReader);
        mandatoryStepBackfillService =
                new MandatoryStepBackfillService(groupService, stepInstanceScope, stepCreationService);

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
        if (requiredBehavior != null) {
            action.setRequiredBehavior(ActionRequiredBehavior.fromCode(requiredBehavior));
        }
        return action;
    }

    /** A step group. It declares no requiredBehavior: every group is optional for now (plan point 11). */
    private static PlanDefinitionActionComponent group(PlanDefinitionActionComponent action, String id) {
        step(action, id, null).setGroupingBehavior(ActionGroupingBehavior.LOGICALGROUP);
        return action;
    }

    /** A mandatory step group, for the later phase that allows one. Used only by disabled tests until then. */
    private static PlanDefinitionActionComponent mandatoryGroup(PlanDefinitionActionComponent action, String id) {
        step(action, id, "must").setGroupingBehavior(ActionGroupingBehavior.LOGICALGROUP);
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

    /**
     * Like {@link #use}, without the load check that refuses a mandatory group. Mandatory groups come in a
     * later phase (plan point 11): the Protocol Service refuses them at load for now, but the engine already
     * handles them, and these tests keep that code working until the check is lifted.
     */
    private void useWithMandatoryGroups(PlanDefinition pd) {
        PlanDefinitionParser parser = new PlanDefinitionParser(fhirContext);
        List<PlanDefinitionParser.StepMetadata> steps = parser.extractSteps(pd);
        List<PlanDefinitionParser.GroupMetadata> groups = parser.extractGroups(pd);
        protocol = new ParsedProtocolCache.ParsedProtocol(
                steps, PlanDefinitionParser.buildDependencyGraph(steps, groups), groups);
    }

    /** What {@code MatcherEngine.processMatchedStep} does with a matched event, at clinical time {@code at}. */
    private StepInstance record(String actionId, OffsetDateTime at) {
        StepInstance row = rowFor(actionId, at);
        completeAndFollowUp(row, at);
        return row;
    }

    /**
     * What {@code MatcherEngine.processMatchedStep} does with a step instance: complete it, then run what
     * follows from the completion, in the same order.
     */
    private void completeAndFollowUp(StepInstance stepInstance, OffsetDateTime at) {
        service.completeStepInstance(stepInstance, UUID.randomUUID(), "test", at);
        Enrollment enrollment = enrollmentLoader.lockAndLoad(stepInstance.getProtocolInstance());
        orderViolationDetector.detectOrderViolations(enrollment, stepInstance);
        progressiveInstantiationService.instantiateDependents(enrollment, stepInstance);
        mandatoryStepBackfillService.backfillMissingMandatorySteps(enrollment, stepInstance);
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
            PlanDefinitionActionComponent review = group(pd.addAction(), "review");
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
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2");
            after(visit, "registration", 42);
            step(visit.addAction(), "bp", "must");
            step(visit.addAction(), "weight", "must");
            step(visit.addAction(), "counselling", "could");
            PlanDefinitionActionComponent nutrition = group(visit.addAction(), "nutrition");
            step(nutrition.addAction(), "diet", "must");
            PlanDefinitionActionComponent followUp = group(pd.addAction(), "follow-up");
            after(followUp, "registration", 7);
            step(followUp.addAction(), "call", "must");
            use(pd);
        }

        @Test
        void aGroupFollowingAStepOpensOnAnEventForOneOfItsSteps_withItsPlannedDueDate() {
            record("registration", T0);
            assertTrue(groupInstanceOf("visit-2").isEmpty(),
                    "every group is optional for now: a step it follows completing opens none");

            record("weight", T0.plusDays(41));

            StepGroupInstance visit = groupInstanceOf("visit-2").orElseThrow();
            assertEquals(T0.plusDays(42), visit.getDueDate());
            assertEquals(List.of(T0.plusDays(42)), rowsFor("bp").stream().map(StepInstance::getDueDate).toList());
            assertTrue(rowsFor("counselling").isEmpty(), "optional steps are never created in advance");
            assertTrue(groupInstanceOf("nutrition").isEmpty(), "nor are nested groups, all optional for now");
            assertTrue(groupInstanceOf("follow-up").isEmpty());
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
            record("weight", T0.plusDays(40));
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
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit");
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
            PlanDefinitionActionComponent journey = group(pd.addAction(), "journey");
            step(journey.addAction(), "check-in", "must");
            PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation");
            after(consultation, "check-in", 0);
            step(consultation.addAction(), "history", "must");
            after(step(consultation.addAction(), "examination", "must"), "history", 0);
            use(pd);
        }

        @Test
        void aNestedGroupOpensInsideItsParentsGroupInstance() {
            record("check-in", T0);
            assertTrue(groupInstanceOf("consultation").isEmpty(), "an optional group doesn't open with its parent");

            record("history", T0.plusMinutes(20));

            StepGroupInstance journey = groupInstanceOf("journey").orElseThrow();
            StepGroupInstance consultation = groupInstanceOf("consultation").orElseThrow();
            assertEquals(journey.getId(), consultation.getParentGroupInstance().getId());
            assertEquals(T0, consultation.getDueDate(), "planned from check-in, the step it follows");
            assertEquals(consultation.getId(), groupInstanceIdOf(rowsFor("history").get(0)));
            assertEquals(consultation.getId(), groupInstanceIdOf(rowsFor("examination").get(0)),
                    "examination follows history, in the same consultation");
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
            PlanDefinitionActionComponent group1 = group(pd.addAction(), "group-1");
            PlanDefinitionActionComponent group2 = group(group1.addAction(), "group-2");
            PlanDefinitionActionComponent group3 = repeatingBy(group(group2.addAction(), "group-3"), "calendar-day");
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
            PlanDefinitionActionComponent group1 = repeatingBy(group(pd.addAction(), "group-1"), "calendar-day");
            PlanDefinitionActionComponent group2 = group(group1.addAction(), "group-2");
            PlanDefinitionActionComponent group3 = namingEntryStep(repeatingBy(group(group2.addAction(), "group-3"), "entry-step"), "step-1");
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
    void aGroupInsideACalendarDayGroupCannotRepeatByCalendarDay() {
        PlanDefinition pd = new PlanDefinition();
        PlanDefinitionActionComponent group1 = repeatingBy(group(pd.addAction(), "group-1"), "calendar-day");
        PlanDefinitionActionComponent group2 = repeatingBy(group(group1.addAction(), "group-2"), "calendar-day");
        step(group2.addAction(), "step-1", "must");

        assertThrows(IllegalArgumentException.class, () -> use(pd));
    }

    @Nested
    class VisitsInAnEpisode {

        /** In the past: an event dated after the test's clock is recorded at the clock's time. */
        private static final OffsetDateTime NEXT_PREGNANCY = T0.plusDays(200);

        /** A pregnancy repeating by entry step, registration; inside it, an ANC visit repeating by calendar day. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent pregnancy =
                    namingEntryStep(repeatingBy(group(pd.addAction(), "pregnancy"), "entry-step"), "registration");
            step(pregnancy.addAction(), "registration", "must");
            PlanDefinitionActionComponent visit = repeatingBy(group(pregnancy.addAction(), "visit"), "calendar-day");
            step(visit.addAction(), "bp", "must");
            use(pd);
        }

        @Test
        void eachDayIsAVisitInThePregnancy_andANewRegistrationStartsTheNextPregnancyWithItsOwnVisits() {
            record("registration", T0);
            assertTrue(cyclesOf("visit", cyclesOf("pregnancy", null).get(0)).isEmpty(),
                    "registration opens no visit: only an event of its own does");

            record("bp", T0.plusDays(1));
            record("bp", T0.plusDays(1).plusHours(1));
            record("bp", T0.plusDays(30));

            List<StepGroupInstance> pregnancies = cyclesOf("pregnancy", null);
            assertEquals(1, pregnancies.size(), "a visit's BP is not the pregnancy's entry step");
            assertEquals(2, cyclesOf("visit", pregnancies.get(0)).size(), "one visit per day");

            record("registration", NEXT_PREGNANCY);
            record("bp", NEXT_PREGNANCY.plusDays(1));

            pregnancies = cyclesOf("pregnancy", null);
            assertEquals(2, pregnancies.size());
            assertEquals(1, cyclesOf("visit", pregnancies.get(1)).size(), "the next pregnancy's visits start at cycle 0");
            assertEquals(0, cyclesOf("visit", pregnancies.get(1)).get(0).getCycleIndex());
        }
    }

    @Nested
    class EntryStep {

        /** A Consultation repeating by entry step, of history and BP, naming history as its entry step. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent consultation =
                    namingEntryStep(repeatingBy(group(pd.addAction(), "consultation"), "entry-step"), "history");
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
    class FirstStepsKeepTheirOwnOffset {

        /** Registration; a visit holding BP, 7 days after registration, and weight, which follows nothing. */
        private void visitAfterRegistration(Integer visitOffsetDays, String boundary) {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "registration", "must");
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit");
            if (boundary != null) {
                repeatingBy(visit, boundary);
            }
            if (visitOffsetDays != null) {
                after(visit, "registration", visitOffsetDays);
            }
            after(step(visit.addAction(), "bp", "must"), "registration", 7);
            step(visit.addAction(), "weight", "must");
            use(pd);
        }

        @Test
        void aStepThatFollowsAStepOutsideTheGroup_isDueByItsOwnOffset() {
            visitAfterRegistration(null, null);

            record("registration", T0);

            assertEquals(T0.plusDays(7), rowsFor("bp").get(0).getDueDate());
            assertNull(rowsFor("weight").get(0).getDueDate(), "weight follows nothing: the visit's due date, none");
        }

        @Test
        void itsOwnOffsetWins_overTheGroupsDueDate() {
            visitAfterRegistration(14, null);

            record("registration", T0);

            assertEquals(T0.plusDays(7), rowsFor("bp").get(0).getDueDate());
            assertEquals(T0.plusDays(14), rowsFor("weight").get(0).getDueDate());
        }

        @Test
        void whileTheStepItFollowsIsNotDone_itIsNotCreated_andComesWithItsOwnOffsetWhenItIs() {
            visitAfterRegistration(null, null);

            record("weight", T0);
            assertTrue(rowsFor("bp").isEmpty(), "BP waits for registration");

            record("registration", T0.plusDays(1));

            assertEquals(1, rowsFor("bp").size());
            assertEquals(T0.plusDays(8), rowsFor("bp").get(0).getDueDate());
            assertEquals(cyclesOf("visit", null).get(0).getId(), groupInstanceIdOf(rowsFor("bp").get(0)));
        }

        @Test
        void inAVisitThatRepeatsByDay_onlyTheFirstVisitUsesTheOffset() {
            visitAfterRegistration(null, "calendar-day");
            record("registration", T0);

            record("weight", T0.plusDays(3));
            record("weight", T0.plusDays(10));

            List<StepInstance> bps = rowsFor("bp");
            assertEquals(2, bps.size());
            assertEquals(T0.plusDays(7), bps.get(0).getDueDate(), "the first visit: registration + 7 days");
            assertNull(bps.get(1).getDueDate(), "a later visit has no due date");
        }
    }

    @Nested
    class OptionalGroupOpenedByWork {

        /**
         * Registration; an optional lab, whose lab-order (must) follows registration by 3 days and whose
         * lab-note (must) follows nothing; an optional review that itself follows registration; and a
         * follow-up (must) after lab-order.
         */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "registration", "must");
            PlanDefinitionActionComponent lab = group(pd.addAction(), "lab");
            after(step(lab.addAction(), "lab-order", "must"), "registration", 3);
            step(lab.addAction(), "lab-note", "must");
            PlanDefinitionActionComponent review = group(pd.addAction(), "review");
            after(review, "registration", 0);
            step(review.addAction(), "review-note", "must");
            after(step(pd.addAction(), "follow-up", "must"), "lab-order", 0);
            use(pd);
        }

        @Test
        void aMandatoryStepThatFollowsAStepOutside_opensItsOptionalGroup_whichCreatesItsFirstMembers() {
            record("registration", T0);

            assertEquals(1, cyclesOf("lab", null).size(), "work for a must step opens the optional group");
            assertEquals(T0.plusDays(3), rowsFor("lab-order").get(0).getDueDate());
            assertEquals(1, rowsFor("lab-note").size(), "the group then creates its first must members");
            assertTrue(cyclesOf("review", null).isEmpty(),
                    "an optional group that itself follows a step is still not opened by it");
        }

        @Test
        void backfillingAMandatoryStepInAnOptionalGroup_opensTheGroup() {
            record("follow-up", T0);

            assertEquals(1, cyclesOf("lab", null).size());
            assertEquals(StepStatus.NOT_STARTED, rowsFor("lab-order").get(0).getStepStatus());
        }
    }

    @Nested
    class NestedGroupFollowsAStepOutsideItsParent {

        /**
         * Registration; a visit repeating by calendar day, holding weight, a lab group 7 days after
         * registration (with lab-order), and an imaging group whose scan is 7 days after registration.
         */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "registration", "must");
            PlanDefinitionActionComponent visit = repeatingBy(group(pd.addAction(), "visit"), "calendar-day");
            step(visit.addAction(), "weight", "must");
            PlanDefinitionActionComponent lab = mandatoryGroup(visit.addAction(), "lab");
            after(lab, "registration", 7);
            step(lab.addAction(), "lab-order", "must");
            PlanDefinitionActionComponent imaging = mandatoryGroup(visit.addAction(), "imaging");
            after(step(imaging.addAction(), "scan", "must"), "registration", 7);
            useWithMandatoryGroups(pd);
        }

        @Test
        void inTheFirstVisit_theGroupAndTheStepInsideANestedGroup_areDueByTheirOwnOffset() {
            record("registration", T0);
            record("weight", T0.plusDays(1));

            StepGroupInstance visit = cyclesOf("visit", null).get(0);
            assertEquals(T0.plusDays(7), cyclesOf("lab", visit).get(0).getDueDate());
            assertEquals(T0.plusDays(7), rowsFor("lab-order").get(0).getDueDate());
            assertEquals(T0.plusDays(7), rowsFor("scan").get(0).getDueDate());
        }

        @Test
        void inALaterVisit_theyHaveNoDueDate_theOffsetIsAlreadyPast() {
            record("registration", T0);
            record("weight", T0.plusDays(1));
            record("weight", T0.plusDays(30));

            StepGroupInstance secondVisit = cyclesOf("visit", null).get(1);
            assertNull(cyclesOf("lab", secondVisit).get(0).getDueDate());
            assertNull(rowsFor("lab-order").get(1).getDueDate());
            assertNull(rowsFor("scan").get(1).getDueDate(), "lab's cycle is 0, but the visit around it is not");
        }

        @Test
        void whileTheStepItFollowsIsNotDone_theGroupWaits_andOpensWithItsOwnOffsetWhenItIs() {
            record("weight", T0);
            StepGroupInstance visit = cyclesOf("visit", null).get(0);
            assertTrue(cyclesOf("lab", visit).isEmpty(), "registration isn't done: lab waits, as a step would");
            assertTrue(rowsFor("lab-order").isEmpty());

            record("registration", T0.plusHours(1));

            assertEquals(T0.plusHours(1).plusDays(7), cyclesOf("lab", visit).get(0).getDueDate());
            assertEquals(T0.plusHours(1).plusDays(7), rowsFor("lab-order").get(0).getDueDate());
        }

        @Test
        void whenTheStepItFollowsIsDoneDuringALaterVisit_itsOffsetIsStillAhead() {
            record("weight", T0);
            record("weight", T0.plusDays(30));
            record("registration", T0.plusDays(30).plusHours(1));

            StepGroupInstance secondVisit = cyclesOf("visit", null).get(1);
            assertEquals(T0.plusDays(37).plusHours(1), cyclesOf("lab", secondVisit).get(0).getDueDate());
        }
    }

    @Nested
    class OptionalNestedGroupFollowsAStepOutsideItsParent {

        /**
         * Registration; a visit repeating by calendar day, holding weight; a lab group 7 days after registration
         * (lab-order, lab-note); and an imaging group whose scan is 7 days after registration (scan, report).
         * Every group is optional, so lab and imaging open only on an event for one of their steps.
         */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            step(pd.addAction(), "registration", "must");
            PlanDefinitionActionComponent visit = repeatingBy(group(pd.addAction(), "visit"), "calendar-day");
            step(visit.addAction(), "weight", "must");
            PlanDefinitionActionComponent lab = group(visit.addAction(), "lab");
            after(lab, "registration", 7);
            step(lab.addAction(), "lab-order", "must");
            step(lab.addAction(), "lab-note", "must");
            PlanDefinitionActionComponent imaging = group(visit.addAction(), "imaging");
            after(step(imaging.addAction(), "scan", "must"), "registration", 7);
            step(imaging.addAction(), "report", "must");
            use(pd);
        }

        @Test
        void aGroupThatFollowsAStepStaysClosed_aGroupWhoseMustStepFollowsItOpens() {
            record("weight", T0);
            assertTrue(cyclesOf("lab", visitOf(0)).isEmpty(), "an optional group doesn't open with its parent");
            assertTrue(cyclesOf("imaging", visitOf(0)).isEmpty());

            record("registration", T0.plusHours(1));

            assertTrue(cyclesOf("lab", visitOf(0)).isEmpty(), "lab itself follows registration: that opens nothing");
            assertEquals(1, cyclesOf("imaging", visitOf(0)).size(), "scan, a must step, follows it: its work opens imaging");
            assertEquals(T0.plusHours(1).plusDays(7), rowsFor("scan").get(0).getDueDate());
        }

        @Test
        void openedByAnEventInTheFirstVisit_theyAreDueByTheirOwnOffset() {
            record("registration", T0);
            record("weight", T0.plusDays(1));
            record("lab-order", T0.plusDays(1).plusHours(1));
            record("report", T0.plusDays(1).plusHours(2));

            assertEquals(T0.plusDays(7), cyclesOf("lab", visitOf(0)).get(0).getDueDate());
            assertEquals(T0.plusDays(7), rowsFor("lab-note").get(0).getDueDate());
            assertEquals(T0.plusDays(7), rowsFor("scan").get(0).getDueDate());
        }

        @Test
        void openedByAnEventInALaterVisit_theyHaveNoDueDate_theOffsetIsAlreadyPast() {
            record("registration", T0);
            record("weight", T0.plusDays(1));
            record("weight", T0.plusDays(30));
            record("lab-order", T0.plusDays(30).plusHours(1));
            record("report", T0.plusDays(30).plusHours(2));

            assertNull(cyclesOf("lab", visitOf(1)).get(0).getDueDate());
            assertNull(rowsFor("lab-note").get(0).getDueDate());
            assertNull(rowsFor("scan").get(0).getDueDate(), "imaging's cycle is 0, but the visit around it is not");
        }

        private StepGroupInstance visitOf(int cycleIndex) {
            return cyclesOf("visit", null).get(cycleIndex);
        }
    }

    @Nested
    class StepFollowsAStepInAGroupAroundItsGroup {

        /** In the past: an event dated after the test's clock is recorded at the clock's time. */
        private static final OffsetDateTime NEXT_PREGNANCY = T0.plusDays(200);

        /** A pregnancy repeating by entry step, registration; inside it, a visit per day with weight and BP. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent pregnancy =
                    namingEntryStep(repeatingBy(group(pd.addAction(), "pregnancy"), "entry-step"), "registration");
            step(pregnancy.addAction(), "registration", "must");
            PlanDefinitionActionComponent visit = repeatingBy(group(pregnancy.addAction(), "visit"), "calendar-day");
            step(visit.addAction(), "weight", "must");
            after(step(visit.addAction(), "bp", "must"), "registration", 7);
            use(pd);
        }

        @Test
        void eachPregnancysFirstVisit_countsFromItsOwnRegistration() {
            record("registration", T0);
            record("weight", T0.plusDays(1));
            record("registration", NEXT_PREGNANCY);
            record("weight", NEXT_PREGNANCY.plusDays(1));

            assertEquals(T0.plusDays(7), rowsFor("bp").get(0).getDueDate());
            assertEquals(NEXT_PREGNANCY.plusDays(7), rowsFor("bp").get(1).getDueDate(),
                    "a new pregnancy brings its own registration: not a later cycle for it");
        }
    }

    @Nested
    class EntryStepFollowsAStepOutsideItsGroup {

        /** Group 1 of step 1 and group 2, repeating by entry step, whose entry step follows step 1 by 2 days. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent group1 = group(pd.addAction(), "group-1");
            step(group1.addAction(), "step-1", "must");
            PlanDefinitionActionComponent group2 =
                    namingEntryStep(repeatingBy(group(group1.addAction(), "group-2"), "entry-step"), "g2-step-1");
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
    class EntryStepInANestedGroup {

        /** Group 1 repeating by entry step, of intake and group 2; its entry step, step 1, is in group 2. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent group1 =
                    namingEntryStep(repeatingBy(group(pd.addAction(), "group-1"), "entry-step"), "step-1");
            step(group1.addAction(), "intake", "must");
            PlanDefinitionActionComponent group2 = group(group1.addAction(), "group-2");
            step(group2.addAction(), "step-1", "must");
            use(pd);
        }

        @Test
        void eachStep1StartsGroup1sNextCycle_withAFreshGroup2InsideIt() {
            record("step-1", T0);
            record("step-1", T0.plusMinutes(10));

            List<StepGroupInstance> group1Cycles = cyclesOf("group-1", null);
            assertEquals(2, group1Cycles.size());
            assertEquals(1, cyclesOf("group-2", group1Cycles.get(0)).size());
            assertEquals(1, cyclesOf("group-2", group1Cycles.get(1)).size());
        }

        @Test
        void intakeFirst_opensCycle0_withoutStep1_andStep1StartsCycle1() {
            record("intake", T0);

            assertTrue(cyclesOf("group-2", cyclesOf("group-1", null).get(0)).isEmpty(),
                    "group 2 is optional, so it doesn't open with group 1");
            assertTrue(rowsFor("step-1").isEmpty(), "group 1's entry step is left to its event");

            record("step-1", T0.plusMinutes(10));

            assertEquals(2, cyclesOf("group-1", null).size());
        }
    }

    @Nested
    class EntryStepParent {

        /** Group 1 repeating by entry step, holding its entry step intake and group 2, which holds step 1. */
        @BeforeEach
        void protocol() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent group1 = namingEntryStep(repeatingBy(group(pd.addAction(), "group-1"), "entry-step"), "intake");
            step(group1.addAction(), "intake", "must");
            PlanDefinitionActionComponent group2 = group(group1.addAction(), "group-2");
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

    /**
     * The Rwanda outpatient protocol written with step groups
     * ({@code fhir/rwanda-outpatient-journey-step-groups.json}): an outpatient visit, one per calendar day,
     * holding a check-in, vitals and a consultation. The consultation is a step, with chief complaints,
     * history, lab order and results, diagnosis, treatment and referral under it.
     */
    @Nested
    class RwandaOutpatientProtocol {

        private static final OffsetDateTime DAY_1 = T0;
        private static final OffsetDateTime DAY_4 = T0.plusDays(3);
        private static final OffsetDateTime DAY_15 = T0.plusDays(14);

        @BeforeEach
        void protocol() {
            PlanDefinition pd = fhirContext.newJsonParser().parseResource(PlanDefinition.class,
                    getClass().getResourceAsStream("/fhir/rwanda-outpatient-journey-step-groups.json"));
            // Every check the Protocol Service runs at load.
            PlanDefinitionParser parser = new PlanDefinitionParser(fhirContext);
            parser.validateActionIds(pd);
            parser.validateActionTypes(pd);
            parser.validateTriggers(pd);
            parser.validateOptionalStepDeadlines(pd);
            parser.validateRelationships(pd);
            use(pd);
        }

        private StepGroupInstance visit(int cycleIndex) {
            return cyclesOf("outpatient-visit", null).get(cycleIndex);
        }

        private List<StepInstance> stepInstancesIn(StepGroupInstance visit, String actionId) {
            return rowsFor(actionId).stream().filter(s -> visit.getId().equals(groupInstanceIdOf(s))).toList();
        }

        private void consultationOnDay1() {
            record("visit-encounter", DAY_1);
            record("vitals-recording", DAY_1.plusMinutes(20));
            record("consultation", DAY_1.plusHours(1));
        }

        @Test
        void aVisit_expectsVitalsThenAConsultationThenADiagnosis_eachByItsOwnOffset() {
            record("visit-encounter", DAY_1);
            assertEquals(DAY_1.plusDays(1), rowsFor("vitals-recording").get(0).getDueDate());

            record("vitals-recording", DAY_1.plusMinutes(20));
            StepInstance awaitedConsultation = rowsFor("consultation").get(0);
            assertEquals(StepStatus.NOT_STARTED, awaitedConsultation.getStepStatus(),
                    "a visit with no consultation is reported: it is awaited after vitals");
            assertEquals(DAY_1.plusMinutes(20).plusDays(1), awaitedConsultation.getDueDate());

            record("consultation", DAY_1.plusHours(1));
            assertEquals(1, rowsFor("consultation").size(), "it completes the awaited one");
            assertEquals(DAY_1.plusHours(1).plusDays(1), rowsFor("diagnosis").get(0).getDueDate(),
                    "a diagnosis is expected after the consultation, with or without a lab test");
        }

        @Test
        void eventsLaterThatDay_joinThatDaysVisit() {
            consultationOnDay1();
            record("lab-order", DAY_1.plusHours(2));
            record("lab-results", DAY_1.plusHours(6));
            record("diagnosis", DAY_1.plusHours(7));

            assertEquals(1, cyclesOf("outpatient-visit", null).size());
            assertEquals(1, stepInstancesIn(visit(0), "lab-results").size());
            assertEquals(1, rowsFor("diagnosis").size(), "the awaited diagnosis is completed, not added to");
            assertEquals(StepStatus.COMPLETED, rowsFor("diagnosis").get(0).getStepStatus());
        }

        @Test
        void labResultsOnALaterDay_openThatDaysVisit_andTheFirstVisitsDiagnosisStaysAwaited() {
            consultationOnDay1();
            record("lab-order", DAY_1.plusHours(2));

            record("lab-results", DAY_4);
            record("diagnosis", DAY_4.plusHours(1));

            assertEquals(2, cyclesOf("outpatient-visit", null).size(), "a new day is a new visit");
            assertEquals(1, stepInstancesIn(visit(1), "lab-results").size());
            assertEquals(StepStatus.COMPLETED, stepInstancesIn(visit(1), "diagnosis").get(0).getStepStatus());
            assertEquals(StepStatus.NOT_STARTED, stepInstancesIn(visit(0), "diagnosis").get(0).getStepStatus(),
                    "the first visit's diagnosis is still awaited, so it will be missed");
            assertEquals(StepStatus.NOT_STARTED, stepInstancesIn(visit(1), "vitals-recording").get(0).getStepStatus(),
                    "the new visit's mandatory steps are backfilled");
            assertEquals(StepStatus.NOT_STARTED, stepInstancesIn(visit(1), "consultation").get(0).getStepStatus());
        }

        @Test
        void aSecondConsultationThatDay_joinsTheSameVisit_withOneDiagnosis() {
            consultationOnDay1();
            record("diagnosis", DAY_1.plusHours(2));

            record("consultation", DAY_1.plusHours(5));

            assertEquals(1, cyclesOf("outpatient-visit", null).size());
            assertEquals(2, stepInstancesIn(visit(0), "consultation").size());
            assertEquals(1, rowsFor("diagnosis").size(), "no second diagnosis is expected");
        }

        @Test
        void eachDay_startsANewVisit_withItsOwnVitalsAndConsultation() {
            consultationOnDay1();
            record("diagnosis", DAY_1.plusHours(2));

            record("visit-encounter", DAY_15);
            record("vitals-recording", DAY_15.plusMinutes(20));

            assertEquals(2, cyclesOf("outpatient-visit", null).size());
            assertEquals(DAY_15.plusDays(1), stepInstancesIn(visit(1), "vitals-recording").get(0).getDueDate());
            StepInstance awaitedConsultation = stepInstancesIn(visit(1), "consultation").get(0);
            assertEquals(StepStatus.NOT_STARTED, awaitedConsultation.getStepStatus());
            assertEquals(DAY_15.plusMinutes(20).plusDays(1), awaitedConsultation.getDueDate());
        }

        @Test
        void vitalsBeforeTheCheckIn_openThatDaysVisit_andTheCheckInJoinsIt() {
            record("visit-encounter", DAY_1);
            record("vitals-recording", DAY_1.plusMinutes(20));

            record("vitals-recording", DAY_15);       // sent before that day's check-in
            record("visit-encounter", DAY_15.plusMinutes(5));

            assertEquals(2, cyclesOf("outpatient-visit", null).size());
            assertEquals(StepStatus.COMPLETED, stepInstancesIn(visit(1), "vitals-recording").get(0).getStepStatus());
            assertEquals(StepStatus.COMPLETED, stepInstancesIn(visit(1), "visit-encounter").get(0).getStepStatus());
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
            PlanDefinitionActionComponent journey = repeatingBy(group(pd.addAction(), "journey"), "calendar-day");
            step(journey.addAction(), "check-in", "must");
            PlanDefinitionActionComponent consultation =
                    namingEntryStep(repeatingBy(group(journey.addAction(), "consultation"), "entry-step"), "history");
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
            PlanDefinitionActionComponent journey = repeatingBy(group(pd.addAction(), "journey"), "calendar-day");
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
            PlanDefinitionActionComponent journey = repeatingBy(group(pd.addAction(), "journey"), "calendar-day");
            after(journey, "registration", 0);
            step(journey.addAction(), "check-in", "must");
            after(step(journey.addAction(), "consent", "must"), "registration", 0);
            PlanDefinitionActionComponent triage = group(journey.addAction(), "triage");
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
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit");
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
            completeAndFollowUp(examination, T0.plusDays(14));

            StepInstance prescription = rowsFor("prescription").get(0);
            assertEquals(current.getId(), groupInstanceIdOf(prescription));
            completeAndFollowUp(prescription, T0.plusDays(14).plusMinutes(30));

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
            completeAndFollowUp(latest, T0.plusDays(14));

            StepInstance row = rowFor("examination", T0.plusDays(1));

            assertNotSame(undone, row);
            assertEquals(current.getId(), groupInstanceIdOf(row), "a further row in the latest cycle");
            assertEquals(StepStatus.NOT_STARTED, undone.getStepStatus());
        }

        @Test
        void aPredecessorMissingFromThisCycleIsBackfilledIntoIt_evenThoughAnEarlierCycleHasOne() {
            StepInstance done = stepCreationService.createStep(enrollment, "examination", 0, T0, null, "must", earlier);
            completeAndFollowUp(done, T0);
            int examinationsBefore = rowsFor("examination").size();

            StepInstance prescription = stepCreationService.createStep(
                    enrollment, "prescription", 0, T0.plusDays(14), null, "must", current);
            completeAndFollowUp(prescription, T0.plusDays(14));

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
            PlanDefinitionActionComponent visit = mandatoryGroup(pd.addAction(), "visit");
            step(visit.addAction(), "intake", "must");
            PlanDefinitionActionComponent measurements = mandatoryGroup(visit.addAction(), "measurements");
            step(measurements.addAction(), "bp", "must");
            useWithMandatoryGroups(pd);
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
            PlanDefinitionActionComponent review = mandatoryGroup(pd.addAction(), "review");
            afterStart(review, "assessment", 10);
            after(review, "labs", 5);
            step(review.addAction(), "summary", "must");
            useWithMandatoryGroups(pd);
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
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit");
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
