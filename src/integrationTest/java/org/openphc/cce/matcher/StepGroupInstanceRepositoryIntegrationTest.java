package org.openphc.cce.matcher;

import org.junit.jupiter.api.Test;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepGroupInstance;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.openphc.cce.common.repository.StepGroupInstanceRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link StepGroupInstanceRepository#findLatestCycle} against the real schema. */
class StepGroupInstanceRepositoryIntegrationTest extends IntegrationTestBase {

    private static final String FIXTURE = "src/integrationTest/resources/fhir/plan-definition-step-group.json";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 10, 9, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private StepGroupInstanceRepository stepGroupInstanceRepository;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Test
    void theLatestCycleIsTheHighestUnderTheGivenParent_orAtTheTopLevel() throws Exception {
        ProtocolDefinition protocolDefinition = protocolDefinitionRepository
                .findById(seedProtocolRows(FIXTURE, "1.0.0-latest-cycle")).orElseThrow();
        ProtocolInstance enrollment = protocolInstanceRepository.save(ProtocolInstance.builder()
                .patientId("patient-latest-cycle-" + UUID.randomUUID())
                .protocolDefinition(protocolDefinition)
                .enrolledAt(T0)
                .status(ProtocolInstanceStatus.ACTIVE)
                .build());

        StepGroupInstance firstVisit = open(enrollment, "journey", null, 0);
        StepGroupInstance secondVisit = open(enrollment, "journey", null, 1);
        open(enrollment, "consultation", firstVisit, 0);
        StepGroupInstance secondInFirstVisit = open(enrollment, "consultation", firstVisit, 1);
        StepGroupInstance firstInSecondVisit = open(enrollment, "consultation", secondVisit, 0);

        assertThat(stepGroupInstanceRepository.findLatestCycle(enrollment.getId(), "journey", null))
                .map(StepGroupInstance::getId).contains(secondVisit.getId());
        assertThat(stepGroupInstanceRepository.findLatestCycle(enrollment.getId(), "consultation", firstVisit.getId()))
                .map(StepGroupInstance::getId).contains(secondInFirstVisit.getId());
        assertThat(stepGroupInstanceRepository.findLatestCycle(enrollment.getId(), "consultation", secondVisit.getId()))
                .map(StepGroupInstance::getId).contains(firstInSecondVisit.getId());
        assertThat(stepGroupInstanceRepository.findLatestCycle(enrollment.getId(), "consultation", null)).isEmpty();
    }

    private StepGroupInstance open(ProtocolInstance enrollment, String groupActionId, StepGroupInstance parent,
                                   int cycleIndex) {
        return stepGroupInstanceRepository.save(StepGroupInstance.builder()
                .protocolInstance(enrollment)
                .parentGroupInstance(parent)
                .groupActionId(groupActionId)
                .cycleIndex(cycleIndex)
                .openedAt(T0.plusDays(cycleIndex))
                .build());
    }
}
