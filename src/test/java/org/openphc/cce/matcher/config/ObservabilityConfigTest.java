package org.openphc.cce.matcher.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.enums.ActionDefinitionStatus;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.repository.ActionDefinitionRepository;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();

    /**
     * Only the repository-backed gauges belong to this binder; counters and timers are registered by
     * the components that increment them, and {@code ActuatorMetricsTest} asserts those against a
     * live context.
     */
    @Test
    void cceMetrics_shouldRegisterRepositoryBackedGauges() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ProtocolInstanceRepository protocolRepo = mock(ProtocolInstanceRepository.class);
        ActionDefinitionRepository actionDefRepo = mock(ActionDefinitionRepository.class);
        when(protocolRepo.countByStatus(ProtocolInstanceStatus.ACTIVE)).thenReturn(5L);
        when(actionDefRepo.countByStatus(ActionDefinitionStatus.ACTIVE)).thenReturn(3L);

        MeterBinder binder = config.cceMetrics(protocolRepo, actionDefRepo);

        binder.bindTo(registry);

        assertNotNull(registry.find("cce.protocol.instances.active").gauge());
        assertEquals(5.0, registry.find("cce.protocol.instances.active").gauge().value());
        assertNotNull(registry.find("cce.action.definitions.active").gauge());
        assertEquals(3.0, registry.find("cce.action.definitions.active").gauge().value());
    }
}
