package org.openphc.cce.matcher;

import org.openphc.cce.common.fhir.ExpressionEvaluationService;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled for the protocol cache refresh
 * ({@link org.openphc.cce.matcher.service.ProtocolDefinitionService#refreshProtocolCaches()}), which
 * reconciles Matcher's in-memory caches against definitions written by the protocol-management service.
 */
// scanBasePackages widened to org.openphc.cce so the @Component beans cce-common-util
// contributes (PlanDefinitionParser, ExpressionEvaluationService) are picked up alongside
// this service's own.
@SpringBootApplication(scanBasePackages = "org.openphc.cce")
// @Entity and @Repository types are not picked up by component scanning, so both are pointed at
// org.openphc.cce as well: the shared entities and repositories live in cce-common-util.
@EntityScan("org.openphc.cce")
@EnableJpaRepositories("org.openphc.cce")
@EnableScheduling
public class MatcherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatcherServiceApplication.class, args);
    }
}
