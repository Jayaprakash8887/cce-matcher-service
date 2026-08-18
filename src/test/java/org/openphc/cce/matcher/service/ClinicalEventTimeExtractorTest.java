package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ClinicalEventTimeExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;
    private ClinicalEventTimeExtractor extractor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        extractor = new ClinicalEventTimeExtractor(meterRegistry);
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    class ChoiceTypeResolution {

        @Test
        void observation_effectiveDateTime_isUsed() {
            JsonNode data = json("""
                    { "resourceType": "Observation", "effectiveDateTime": "2026-03-15T09:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 9, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Observation, data));
        }

        @Test
        void observation_effectivePeriod_prefersStart() {
            JsonNode data = json("""
                    { "resourceType": "Observation",
                      "effectivePeriod": { "start": "2026-03-15T08:00:00Z", "end": "2026-03-15T10:00:00Z" } }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 8, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Observation, data));
        }

        @Test
        void observation_dateTimePreferredOverIssued() {
            JsonNode data = json("""
                    { "resourceType": "Observation",
                      "effectiveDateTime": "2026-03-15T09:00:00Z",
                      "issued": "2026-03-20T00:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 9, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Observation, data));
        }

        @Test
        void observation_fallsBackToIssuedWhenNoEffective() {
            JsonNode data = json("""
                    { "resourceType": "Observation", "issued": "2026-03-20T12:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 20, 12, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Observation, data));
        }

        @Test
        void observation_issuedPreferredOverEffectivePeriodEnd() {
            // effectivePeriod.end is the last-resort candidate — issued must win over it.
            JsonNode data = json("""
                    { "resourceType": "Observation",
                      "effectivePeriod": { "end": "2026-03-15T10:00:00Z" },
                      "issued": "2026-03-20T00:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 20, 0, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Observation, data));
        }

        @Test
        void observation_fallsBackToEffectivePeriodEndAsLastResort() {
            JsonNode data = json("""
                    { "resourceType": "Observation",
                      "effectivePeriod": { "end": "2026-03-15T10:00:00Z" } }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 10, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Observation, data));
        }

        @Test
        void encounter_usesPeriodStart() {
            JsonNode data = json("""
                    { "resourceType": "Encounter",
                      "period": { "start": "2026-03-15T08:00:00Z", "end": "2026-03-15T09:30:00Z" } }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 8, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Encounter, data));
        }

        @Test
        void encounter_fallsBackToPeriodEndWhenNoStart() {
            JsonNode data = json("""
                    { "resourceType": "Encounter", "period": { "end": "2026-03-15T09:30:00Z" } }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 9, 30, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Encounter, data));
        }

        @Test
        void immunization_occurrenceDateTime() {
            JsonNode data = json("""
                    { "resourceType": "Immunization", "occurrenceDateTime": "2026-01-02T00:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Immunization, data));
        }

        @Test
        void medicationRequest_authoredOn() {
            JsonNode data = json("""
                    { "resourceType": "MedicationRequest", "authoredOn": "2026-03-15T09:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 9, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.MedicationRequest, data));
        }

        @Test
        void medicationDispense_prefersWhenHandedOver() {
            JsonNode data = json("""
                    { "resourceType": "MedicationDispense",
                      "whenPrepared": "2026-03-15T08:00:00Z", "whenHandedOver": "2026-03-15T11:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 11, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.MedicationDispense, data));
        }

        @Test
        void medicationDispense_fallsBackToWhenPrepared() {
            JsonNode data = json("""
                    { "resourceType": "MedicationDispense", "whenPrepared": "2026-03-15T08:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 8, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.MedicationDispense, data));
        }

        @Test
        void consent_dateTime() {
            JsonNode data = json("""
                    { "resourceType": "Consent", "dateTime": "2026-03-15T09:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 15, 9, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.Consent, data));
        }

        @Test
        void allergyIntolerance_prefersOnsetOverRecordedDate() {
            JsonNode data = json("""
                    { "resourceType": "AllergyIntolerance",
                      "onsetDateTime": "2026-03-10T00:00:00Z", "recordedDate": "2026-03-15T00:00:00Z" }
                    """);
            assertEquals(OffsetDateTime.of(2026, 3, 10, 0, 0, 0, 0, ZoneOffset.UTC),
                    extractor.extract(ResourceType.AllergyIntolerance, data));
        }
    }

    @Nested
    class PartialPrecision {

        @Test
        void yearMonthPrecision_parsesToNonNull() {
            // HAPI resolves partial-precision dates to the start of the precision; we only assert
            // it is derivable (exact instant depends on the JVM default zone for date-only values).
            JsonNode data = json("""
                    { "resourceType": "Observation", "effectiveDateTime": "2026-03" }
                    """);
            assertNotNull(extractor.extract(ResourceType.Observation, data));
        }
    }

    @Nested
    class SafeFallback {

        @Test
        void unmappedResourceType_returnsNull_andMeters() {
            JsonNode data = json("""
                    { "resourceType": "CarePlan", "created": "2026-03-15T09:00:00Z" }
                    """);
            assertNull(extractor.extract(ResourceType.CarePlan, data));
            assertEquals(1.0, meterRegistry.counter("cce.clinical_time.unmapped",
                    "resourceType", "CarePlan").count());
        }

        @Test
        void mappedTypeButNoClinicalField_returnsNull() {
            JsonNode data = json("""
                    { "resourceType": "Observation", "status": "final" }
                    """);
            assertNull(extractor.extract(ResourceType.Observation, data));
        }

        @Test
        void unparseableValue_returnsNull_andMeters() {
            JsonNode data = json("""
                    { "resourceType": "Observation", "effectiveDateTime": "not-a-date" }
                    """);
            assertNull(extractor.extract(ResourceType.Observation, data));
            assertEquals(1.0, meterRegistry.counter("cce.clinical_time.unparseable",
                    "resourceType", "Observation").count());
        }

        @Test
        void nullResourceType_returnsNull() {
            assertNull(extractor.extract(null, json("{}")));
        }

        @Test
        void nullData_returnsNull() {
            assertNull(extractor.extract(ResourceType.Observation, null));
        }
    }
}
