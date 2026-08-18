package org.openphc.cce.matcher;

import org.junit.jupiter.api.Test;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.openphc.cce.matcher.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.matcher.service.ConditionOnlyTrigger;
import org.openphc.cce.matcher.service.ProtocolDefinitionService;
import org.openphc.cce.matcher.service.TriggerMatchingService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the reconciliation between Matcher's in-memory caches and the protocol definitions another
 * service writes.
 *
 * <p>Condition-only triggers exist nowhere but memory — they have no {@code data[]} to index — so
 * without this refresh a protocol published while Matcher is running would never match on one, and a
 * retired protocol would keep matching. Each test therefore seeds only the rows the
 * protocol-management service owns and then drives the refresh explicitly, which is what the scheduler
 * does on a timer.
 */
class ProtocolCacheRefreshIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ProtocolDefinitionService protocolDefinitionService;

    @Autowired
    private TriggerMatchingService triggerMatchingService;

    @Autowired
    private ProtocolDefinitionRepository protocolDefinitionRepository;

    @Test
    void protocolPublishedWhileRunning_conditionOnlyTriggersBecomeLiveAfterRefresh() throws Exception {
        UUID protocolDefId = seedProtocolRows(
                "src/test/resources/fhir/plan-definition-anc-high-risk.json",
                "60.0.0-refresh-" + UUID.randomUUID().toString().substring(0, 8));

        // The rows exist, but nothing has told Matcher about them yet
        assertThat(triggersFor(protocolDefId))
                .as("a protocol written by another service is not cached until the refresh runs")
                .isEmpty();

        protocolDefinitionService.refreshProtocolCaches();

        assertThat(triggersFor(protocolDefId))
                .as("the refresh discovers the new protocol and registers its condition-only triggers")
                .isNotEmpty();
    }

    @Test
    void protocolRetiredWhileRunning_conditionOnlyTriggersStopMatchingAfterRefresh() throws Exception {
        UUID protocolDefId = seedProtocolRows(
                "src/test/resources/fhir/plan-definition-anc-high-risk.json",
                "61.0.0-refresh-" + UUID.randomUUID().toString().substring(0, 8));

        protocolDefinitionService.refreshProtocolCaches();
        assertThat(triggersFor(protocolDefId)).isNotEmpty();

        // The management service retires it
        ProtocolDefinition protocolDef = protocolDefinitionRepository.findById(protocolDefId).orElseThrow();
        protocolDef.setStatus(ProtocolDefinitionStatus.RETIRED);
        protocolDefinitionRepository.save(protocolDef);

        protocolDefinitionService.refreshProtocolCaches();

        assertThat(triggersFor(protocolDefId))
                .as("a retired protocol must stop contributing condition-only triggers")
                .isEmpty();
    }

    @Test
    void repeatedRefresh_isIdempotent() throws Exception {
        UUID protocolDefId = seedProtocolRows(
                "src/test/resources/fhir/plan-definition-anc-high-risk.json",
                "62.0.0-refresh-" + UUID.randomUUID().toString().substring(0, 8));

        protocolDefinitionService.refreshProtocolCaches();
        int afterFirst = triggersFor(protocolDefId).size();

        protocolDefinitionService.refreshProtocolCaches();
        protocolDefinitionService.refreshProtocolCaches();

        assertThat(triggersFor(protocolDefId))
                .as("re-registering must replace, never accumulate")
                .hasSize(afterFirst);
    }

    private List<ConditionOnlyTrigger> triggersFor(UUID protocolDefId) {
        return triggerMatchingService.getConditionOnlyTriggers().stream()
                .filter(t -> protocolDefId.equals(t.protocolDefinitionId()))
                .toList();
    }
}
