package org.openphc.cce.matcher.service;

import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.matcher.domain.repository.TriggerIndexRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TriggerMatchingServiceTest {

    @Mock
    private TriggerIndexRepository triggerIndexRepository;

    private TriggerMatchingService service;

    @BeforeEach
    void setUp() {
        service = new TriggerMatchingService(triggerIndexRepository);
    }

    // ── findStructuralMatches ──

    @Nested
    class FindStructuralMatches {

        @Test
        void singleCodeFilter_matchesAction() {
            UUID protDefId = UUID.randomUUID();
            String actionId = "blood-pressure-check";

            when(triggerIndexRepository.findStructuralMatches(eq(ResourceType.Observation), anyList()))
                    .thenReturn(List.<Object[]>of(new Object[]{protDefId, actionId}));

            List<CodePathTriple> codes = List.of(
                    new CodePathTriple("code", "http://loinc.org", "85354-9")
            );
            List<MatchedStep> matches = service.findStructuralMatches(ResourceType.Observation, codes);

            assertEquals(1, matches.size());
            assertEquals(protDefId, matches.get(0).protocolDefinitionId());
            assertEquals(actionId, matches.get(0).actionId());
        }

        @Test
        void andMatching_twoCodeFilters_matchesBoth() {
            UUID protDefId = UUID.randomUUID();
            String actionId = "lab-work";

            when(triggerIndexRepository.findStructuralMatches(eq(ResourceType.Observation), anyList()))
                    .thenReturn(List.<Object[]>of(new Object[]{protDefId, actionId}));

            List<CodePathTriple> codes = List.of(
                    new CodePathTriple("code", "http://loinc.org", "85354-9"),
                    new CodePathTriple("category", "http://terminology.hl7.org/CodeSystem/observation-category", "laboratory")
            );
            List<MatchedStep> matches = service.findStructuralMatches(ResourceType.Observation, codes);

            assertEquals(1, matches.size());
            assertEquals(actionId, matches.get(0).actionId());
        }

        @Test
        void noMatch_returnsEmptyList() {
            when(triggerIndexRepository.findStructuralMatches(eq(ResourceType.Observation), anyList()))
                    .thenReturn(List.of());

            List<CodePathTriple> codes = List.of(
                    new CodePathTriple("code", "http://unknown.org", "unknown")
            );
            List<MatchedStep> matches = service.findStructuralMatches(ResourceType.Observation, codes);

            assertTrue(matches.isEmpty());
        }

        @Test
        void nullResourceType_returnsEmptyList() {
            List<MatchedStep> matches = service.findStructuralMatches(null, List.of());
            assertTrue(matches.isEmpty());
            verifyNoInteractions(triggerIndexRepository);
        }

        @Test
        void blankResourceType_returnsEmptyList() {
            List<MatchedStep> matches = service.findStructuralMatches(null, List.of());
            assertTrue(matches.isEmpty());
            verifyNoInteractions(triggerIndexRepository);
        }

        @Test
        void emptyCodesList_includesEmptyTriple() {
            when(triggerIndexRepository.findStructuralMatches(eq(ResourceType.Encounter), anyList()))
                    .thenReturn(List.of());

            service.findStructuralMatches(ResourceType.Encounter, List.of());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> triplesCaptor = ArgumentCaptor.forClass(List.class);
            verify(triggerIndexRepository).findStructuralMatches(eq(ResourceType.Encounter), triplesCaptor.capture());

            List<String> triples = triplesCaptor.getValue();
            assertTrue(triples.contains("||"), "Should include empty triple for F1 matching");
        }

        @Test
        void alwaysIncludesEmptyTriple_forResourceTypeOnlyMatching() {
            when(triggerIndexRepository.findStructuralMatches(eq(ResourceType.Observation), anyList()))
                    .thenReturn(List.of());

            List<CodePathTriple> codes = List.of(
                    new CodePathTriple("code", "http://loinc.org", "85354-9")
            );
            service.findStructuralMatches(ResourceType.Observation, codes);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> triplesCaptor = ArgumentCaptor.forClass(List.class);
            verify(triggerIndexRepository).findStructuralMatches(eq(ResourceType.Observation), triplesCaptor.capture());

            List<String> triples = triplesCaptor.getValue();
            assertEquals(2, triples.size());
            assertTrue(triples.contains("||"));
            assertTrue(triples.contains("code|http://loinc.org|85354-9"));
        }

        @Test
        void multipleMatches_returnsAll() {
            UUID protDefId1 = UUID.randomUUID();
            UUID protDefId2 = UUID.randomUUID();

            when(triggerIndexRepository.findStructuralMatches(eq(ResourceType.Encounter), anyList()))
                    .thenReturn(List.of(
                            new Object[]{protDefId1, "action-a"},
                            new Object[]{protDefId2, "action-b"}
                    ));

            List<MatchedStep> matches = service.findStructuralMatches(ResourceType.Encounter,
                    List.of(new CodePathTriple("type", "http://snomed.info/sct", "11429006")));

            assertEquals(2, matches.size());
        }

        @Test
        void samePathDifferentSystems_passesDistinctTriples() {
            // RMNCH pattern: two codeFilters on path "identifier" with different systems
            // e.g., encounter-type=ANC AND visit-count=1
            when(triggerIndexRepository.findStructuralMatches(eq(ResourceType.Encounter), anyList()))
                    .thenReturn(List.of());

            List<CodePathTriple> codes = List.of(
                    new CodePathTriple("identifier", "http://mdtlabs.com/encounter-type", "ANC"),
                    new CodePathTriple("identifier", "http://mdtlabs.com/visit-count", "1"),
                    new CodePathTriple("identifier", "http://mdtlabs.com/type", "assessment"),
                    new CodePathTriple("identifier", "http://mdtlabs.com/village-id", "312")
            );
            service.findStructuralMatches(ResourceType.Encounter, codes);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> triplesCaptor = ArgumentCaptor.forClass(List.class);
            verify(triggerIndexRepository).findStructuralMatches(eq(ResourceType.Encounter), triplesCaptor.capture());

            List<String> triples = triplesCaptor.getValue();
            assertEquals(5, triples.size()); // 4 identifiers + 1 empty F1 triple
            assertTrue(triples.contains("||"));
            assertTrue(triples.contains("identifier|http://mdtlabs.com/encounter-type|ANC"));
            assertTrue(triples.contains("identifier|http://mdtlabs.com/visit-count|1"));
            assertTrue(triples.contains("identifier|http://mdtlabs.com/type|assessment"));
            assertTrue(triples.contains("identifier|http://mdtlabs.com/village-id|312"));
        }
    }

    // ── Condition-only triggers ──

    @Nested
    class ConditionOnlyTriggerTests {

        @Test
        void registerAndRetrieve() {
            UUID protDefId = UUID.randomUUID();
            List<ConditionOnlyTrigger> triggers = List.of(
                    new ConditionOnlyTrigger(protDefId, "global-risk", "text/jsonlogic",
                            "{\">\": [{\"var\": \"event.riskScore\"}, 7]}")
            );

            service.registerConditionOnlyTriggers(protDefId, triggers);

            List<ConditionOnlyTrigger> retrieved = service.getConditionOnlyTriggers();
            assertEquals(1, retrieved.size());
            assertEquals("global-risk", retrieved.get(0).actionId());
            assertEquals("text/jsonlogic", retrieved.get(0).conditionLanguage());
        }

        @Test
        void registerMultipleProtocols() {
            UUID protDefId1 = UUID.randomUUID();
            UUID protDefId2 = UUID.randomUUID();

            service.registerConditionOnlyTriggers(protDefId1, List.of(
                    new ConditionOnlyTrigger(protDefId1, "action-1", "text/jsonlogic", "{\"==\":[1,1]}")
            ));
            service.registerConditionOnlyTriggers(protDefId2, List.of(
                    new ConditionOnlyTrigger(protDefId2, "action-2", "text/fhirpath", "Patient.active")
            ));

            List<ConditionOnlyTrigger> all = service.getConditionOnlyTriggers();
            assertEquals(2, all.size());
        }



        @Test
        void registerNull_noOp() {
            service.registerConditionOnlyTriggers(UUID.randomUUID(), null);
            assertTrue(service.getConditionOnlyTriggers().isEmpty());
        }

        @Test
        void registerEmpty_noOp() {
            service.registerConditionOnlyTriggers(UUID.randomUUID(), List.of());
            assertTrue(service.getConditionOnlyTriggers().isEmpty());
        }

    
        @Test
        void reRegisteringProtocol_replacesItsPreviousTriggers() {
            UUID protDefId = UUID.randomUUID();
            service.registerConditionOnlyTriggers(protDefId, List.of(
                    new ConditionOnlyTrigger(protDefId, "action-1", "text/jsonlogic", "{\"==\":[1,1]}")
            ));
            service.registerConditionOnlyTriggers(protDefId, List.of(
                    new ConditionOnlyTrigger(protDefId, "action-2", "text/jsonlogic", "{\"==\":[2,2]}")
            ));

            List<ConditionOnlyTrigger> all = service.getConditionOnlyTriggers();
            assertEquals(1, all.size());
            assertEquals("action-2", all.get(0).actionId());
        }
    }
}
