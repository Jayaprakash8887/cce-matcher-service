package org.openphc.cce.matcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.repository.StepGroupInstanceRepository;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.matcher.service.StepInstanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first events for two steps of one unopened group, processed in concurrent transactions. Each would
 * find no occurrence and open the group, so without the enrollment lock the group could open twice, or a
 * step could get a second row.
 */
class StepGroupConcurrencyIntegrationTest extends IntegrationTestBase {

    private static final String FIXTURE = "src/integrationTest/resources/fhir/plan-definition-step-group.json";

    @Autowired
    private StepInstanceService stepInstanceService;

    @Autowired
    private StepInstanceRepository stepInstanceRepository;

    @Autowired
    private StepGroupInstanceRepository stepGroupInstanceRepository;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Autowired
    private ParsedProtocolCache parsedProtocolCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private ProtocolDefinition protocolDefinition;

    @BeforeEach
    void seedProtocol() throws IOException {
        tx = new TransactionTemplate(transactionManager);
        UUID protocolDefinitionId = seedProtocolRows(FIXTURE, "1.0.0-group-concurrency");
        protocolDefinition = protocolDefinitionRepository.findById(protocolDefinitionId).orElseThrow();
    }

    @RepeatedTest(10)
    void twoFirstEventsAtOnce_openOneOccurrence_withOneRowPerStep() throws Exception {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).truncatedTo(ChronoUnit.MICROS);
        UUID enrollmentId = enroll(at);

        recordAtOnce(enrollmentId, List.of("bp", "weight"), at);

        List<StepGroupInstance> visits =
                stepGroupInstanceRepository.findByProtocolInstanceIdAndGroupActionId(enrollmentId, "visit");
        assertThat(visits).hasSize(1);
        List<StepInstance> rows = stepInstanceRepository.findByProtocolInstanceId(enrollmentId);
        assertThat(rows).extracting(StepInstance::getActionId).containsExactlyInAnyOrder("bp", "weight");
        assertThat(rows).allMatch(row -> visits.get(0).getId().equals(row.getStepGroupInstance().getId()));
        assertThat(rows).allMatch(row -> row.getStepStatus() == StepStatus.COMPLETED);
    }

    private UUID enroll(OffsetDateTime at) {
        return tx.execute(status -> protocolInstanceRepository.save(ProtocolInstance.builder()
                .patientId("patient-group-concurrency-" + UUID.randomUUID())
                .protocolDefinition(protocolDefinition)
                .enrolledAt(at)
                .status(ProtocolInstanceStatus.ACTIVE)
                .build()).getId());
    }

    /**
     * Records each event as {@code MatcherEngine} does for a step in a group, each in its own transaction:
     * find or create the step's row in the occurrence the event goes to, then complete it. Both start
     * together, so each looks for the occurrence while the other may be opening it.
     */
    private void recordAtOnce(UUID enrollmentId, List<String> actionIds, OffsetDateTime at) throws Exception {
        CyclicBarrier bothStarted = new CyclicBarrier(actionIds.size());
        ExecutorService pool = Executors.newFixedThreadPool(actionIds.size());
        try {
            List<Future<?>> events = actionIds.stream()
                    .<Future<?>>map(actionId -> pool.submit(() -> tx.executeWithoutResult(status -> {
                        ProtocolInstance enrollment = protocolInstanceRepository.findById(enrollmentId).orElseThrow();
                        await(bothStarted);
                        StepInstance row = stepInstanceService.findOrCreateGroupedStep(
                                enrollment, metadata(actionId), at, at);
                        stepInstanceService.completeStep(row, UUID.randomUUID(), "test", at);
                    })))
                    .toList();
            for (Future<?> event : events) {
                event.get(30, SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private PlanDefinitionParser.StepMetadata metadata(String actionId) {
        return parsedProtocolCache
                .get(protocolDefinition.getId(), () -> protocolDefinition.getDefinition().toString())
                .step(actionId);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The two events were not processed together", e);
        }
    }
}
