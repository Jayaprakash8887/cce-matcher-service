package org.openphc.cce.matcher.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.repository.StepSlaStateTransitionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepSlaScheduleServiceTest {

    @Mock
    private StepSlaStateTransitionRepository transitionRepository;

    private StepSlaScheduleService service;

    @BeforeEach
    void setUp() {
        service = new StepSlaScheduleService(transitionRepository);
    }

    @Nested
    class Schedule {

        @Test
        void bothThresholds_writesOneRowPerTransition() {
            StepInstance step = buildStep();
            OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            OffsetDateTime missed = due.plusDays(3);

            service.schedule(step, due, missed);

            Map<SlaTransitionType, StepSlaStateTransition> rows = captureRows();
            assertEquals(2, rows.size());

            StepSlaStateTransition toOverdue = rows.get(SlaTransitionType.DUE_DATE_REACHED);
            assertEquals(step.getId(), toOverdue.getStepInstance().getId());
            assertEquals(due, toOverdue.getProcessBy());
            // The row names the deadline, not a from/to pair: what crossing it means for the step is
            // decided when Step SLA applies it, and read back from step_instance.sla_status.
            assertEquals(SlaStatus.OVERDUE, SlaTransitionType.DUE_DATE_REACHED.breachStatus());

            StepSlaStateTransition toMissed = rows.get(SlaTransitionType.MISSED_DATE_REACHED);
            assertEquals(missed, toMissed.getProcessBy());
            assertEquals(SlaStatus.MISSED, SlaTransitionType.MISSED_DATE_REACHED.breachStatus());
        }

        @Test
        void rowsAreWrittenUnprocessedWithTheGateAtTheThreshold() {
            StepInstance step = buildStep();
            OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);

            service.schedule(step, due, null);

            StepSlaStateTransition row = captureRows().get(SlaTransitionType.DUE_DATE_REACHED);
            // This service never marks a row done — that belongs to the evaluating service.
            assertFalse(row.isProcessed());
            assertNull(row.getProcessedAt());
            assertNull(row.getProcessedBy());
            assertEquals(0, row.getAttempts());
            // next_attempt_at starts at the threshold, so the row is claimable the moment it is due.
            assertEquals(due, row.getNextAttemptAt());
        }

        @Test
        void missingMissedDate_schedulesOnlyTheDueTransition() {
            StepInstance step = buildStep();
            OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);

            service.schedule(step, due, null);

            // An absent threshold gets no row — there is nothing for the evaluator to fire.
            Map<SlaTransitionType, StepSlaStateTransition> rows = captureRows();
            assertEquals(1, rows.size());
            assertTrue(rows.containsKey(SlaTransitionType.DUE_DATE_REACHED));
        }

        @Test
        void noThresholds_writesNothing() {
            service.schedule(buildStep(), null, null);

            // A step that cannot go overdue has no transition to schedule.
            verify(transitionRepository, never()).saveAll(any());
        }
    }

    @Nested
    class OptionalSteps {

        @Test
        void optionalStep_schedulesNothingEvenWithBothThresholds() {
            // Nothing is required of an optional step, so there is nothing it can fail to do by a
            // deadline. Rows for one would only schedule a judgement the Step SLA Service declines.
            OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);

            service.schedule(buildStep("could"), due, due.plusDays(3));

            verify(transitionRepository, never()).saveAll(any());
        }

        @Test
        void absentRequiredBehaviourIsOptionalToo() {
            // "must" is the only thing that makes a step mandatory — an unstated requiredBehavior
            // states no requirement, which is the reading progressive instantiation takes as well.
            OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);

            service.schedule(buildStep(null), due, due.plusDays(3));

            verify(transitionRepository, never()).saveAll(any());
        }
    }

    @Nested
    class MetOnCompletion {

        @Test
        void onTimeCompletion_schedulesTheMetTransitionAtTheCompletionItself() {
            // process_by is the completed_at that satisfied the condition, so the row is due at once
            // and the Step SLA Service records MET on its next cycle — not at a due date days away.
            StepInstance step = buildStep();
            OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(4);
            step.setDueDate(due);
            OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);

            service.scheduleMetIfOnTime(step, completedAt);

            StepSlaStateTransition row = captureSavedRow();
            assertEquals(SlaTransitionType.MET_CONDITION_REACHED, row.getTransitionType());
            assertEquals(step.getId(), row.getStepInstance().getId());
            assertEquals(completedAt, row.getProcessBy());
            assertEquals(completedAt, row.getNextAttemptAt());
            // Applying it is the other service's job, as with every other row.
            assertFalse(row.isProcessed());
            assertEquals(0, row.getAttempts());
        }

        @Test
        void lateCompletion_schedulesNothing() {
            // The step's own DUE_DATE_REACHED row is already scheduled to catch this; there is no MET
            // to record and nothing to ask.
            StepInstance step = buildStep();
            step.setDueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

            service.scheduleMetIfOnTime(step, OffsetDateTime.now(ZoneOffset.UTC));

            verify(transitionRepository, never()).save(any());
        }

        @Test
        void completionExactlyAtTheDueDate_schedulesNothing() {
            // Same boundary the Step SLA Service judges on: at the deadline is not before it.
            StepInstance step = buildStep();
            OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
            step.setDueDate(due);

            service.scheduleMetIfOnTime(step, due);

            verify(transitionRepository, never()).save(any());
        }

        @Test
        void stepWithNoDueDate_schedulesNothing() {
            // Nothing to have beaten — a step created from its own trigger is the usual case.
            service.scheduleMetIfOnTime(buildStep(), OffsetDateTime.now(ZoneOffset.UTC));

            verify(transitionRepository, never()).save(any());
        }

        @Test
        void optionalStep_schedulesNothing() {
            // An optional step has no deadline at all, so no due date it can be said to have beaten.
            StepInstance step = buildStep("could");
            step.setDueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(4));

            service.scheduleMetIfOnTime(step, OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));

            verify(transitionRepository, never()).save(any());
        }
    }

    // ── Helpers ──

    private StepSlaStateTransition captureSavedRow() {
        ArgumentCaptor<StepSlaStateTransition> captor =
                ArgumentCaptor.forClass(StepSlaStateTransition.class);
        verify(transitionRepository).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<SlaTransitionType, StepSlaStateTransition> captureRows() {
        ArgumentCaptor<List<StepSlaStateTransition>> captor = ArgumentCaptor.forClass(List.class);
        verify(transitionRepository).saveAll(captor.capture());
        return captor.getValue().stream()
                .collect(Collectors.toMap(StepSlaStateTransition::getTransitionType, Function.identity()));
    }

    private static StepSlaStateTransition row(UUID stepId, SlaTransitionType type, OffsetDateTime processBy) {
        return StepSlaStateTransition.builder()
                .id(UUID.randomUUID())
                .stepInstance(StepInstance.builder().id(stepId).build())
                .transitionType(type)
                .processBy(processBy)
                .nextAttemptAt(processBy)
                .build();
    }

    /** A mandatory step: the only kind that is scheduled at all. */
    private static StepInstance buildStep() {
        return buildStep("must");
    }

    private static StepInstance buildStep(String requiredBehavior) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .actionId("bp-check")
                .repeatIndex(0)
                .stepStatus(StepStatus.NOT_STARTED)
                .requiredBehavior(requiredBehavior)
                .build();
    }
}
