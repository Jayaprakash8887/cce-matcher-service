package org.openphc.cce.matcher.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.openphc.cce.common.enums.ActionDefinitionStatus;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.repository.ActionDefinitionRepository;
import org.openphc.cce.common.repository.ProtocolInstanceRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the meters that have no natural owner in the request path.
 *
 * <p>Counters and timers are registered — with their descriptions — where they are incremented, so
 * there is a single place to change when one is added or renamed. Only these gauges live here,
 * because they poll repositories rather than being driven by application code.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterBinder cceMetrics(ProtocolInstanceRepository protocolInstanceRepository,
                                  ActionDefinitionRepository actionDefinitionRepository) {
        return registry -> {
            Gauge.builder("cce.protocol.instances.active",
                            protocolInstanceRepository,
                            repo -> repo.countByStatus(ProtocolInstanceStatus.ACTIVE))
                    .description("Number of currently active protocol instances")
                    .register(registry);

            Gauge.builder("cce.action.definitions.active",
                            actionDefinitionRepository,
                            repo -> repo.countByStatus(ActionDefinitionStatus.ACTIVE))
                    .description("Number of currently active action definitions")
                    .register(registry);
        };
    }
}
