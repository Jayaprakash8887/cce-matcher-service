package org.openphc.cce.matcher.config;

import org.openphc.cce.common.config.FhirConfig;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FhirConfigTest {

    private final FhirConfig fhirConfig = new FhirConfig();

    @Test
    void fhirContext_shouldReturnR4Context() {
        FhirContext context = fhirConfig.fhirContext();

        assertNotNull(context);
        assertEquals(FhirVersionEnum.R4, context.getVersion().getVersion());
    }

    @Test
    void fhirContext_multipleCalls_shouldAllBeR4() {
        FhirContext first = fhirConfig.fhirContext();
        FhirContext second = fhirConfig.fhirContext();

        // Both instances must be R4; Spring @Bean singleton scope
        // ensures only one instance is used at runtime
        assertEquals(FhirVersionEnum.R4, first.getVersion().getVersion());
        assertEquals(FhirVersionEnum.R4, second.getVersion().getVersion());
    }
}
