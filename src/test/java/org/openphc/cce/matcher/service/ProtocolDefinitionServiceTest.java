package org.openphc.cce.matcher.service;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.openphc.cce.matcher.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Matcher only reads protocol definitions — loading, retiring and indexing them belongs to the
 * protocol-management service — so the surface under test is lookup plus the periodic reconciliation
 * of the caches derived from them.
 */
@ExtendWith(MockitoExtension.class)
class ProtocolDefinitionServiceTest {

    @Mock
    private ProtocolDefinitionRepository protocolDefinitionRepository;

    @Mock
    private TriggerMatchingService triggerMatchingService;

    @Mock
    private ParsedProtocolCache parsedProtocolCache;

    private ProtocolDefinitionService service;
    private String planDefinitionJson;

    @BeforeEach
    void setUp() throws IOException {
        // A real parser: the point of these tests is that stored JSON yields the right triggers.
        service = new ProtocolDefinitionService(protocolDefinitionRepository,
                new PlanDefinitionParser(FhirContext.forR4()),
                triggerMatchingService, parsedProtocolCache);
        planDefinitionJson = Files.readString(
                Path.of("src/test/resources/fhir/plan-definition-anc-high-risk.json"));
    }

    @Nested
    class FindById {

        @Test
        void existing_returnsDefinition() {
            UUID id = UUID.randomUUID();
            ProtocolDefinition def = buildProtocolDefinition(id);
            when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(def));

            assertSame(def, service.findById(id));
        }

        @Test
        void notFound_throwsEntityNotFound() {
            UUID id = UUID.randomUUID();
            when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> service.findById(id));
        }
    }

    @Nested
    class RefreshProtocolCaches {

        @Test
        void newProtocol_registersItsConditionOnlyTriggers() {
            UUID id = UUID.randomUUID();
            stubActive(fingerprint(id, stamp(1)));
            when(protocolDefinitionRepository.findById(id))
                    .thenReturn(Optional.of(buildProtocolDefinition(id)));

            service.refreshProtocolCaches();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ConditionOnlyTrigger>> captor = ArgumentCaptor.forClass(List.class);
            verify(triggerMatchingService).registerConditionOnlyTriggers(eq(id), captor.capture());
            assertFalse(captor.getValue().isEmpty(),
                    "the ANC protocol declares at least one condition-only trigger");
            assertTrue(captor.getValue().stream().allMatch(t -> id.equals(t.protocolDefinitionId())));
        }

        @Test
        void unchangedProtocol_isNotReparsedOnTheNextSweep() {
            UUID id = UUID.randomUUID();
            stubActive(fingerprint(id, stamp(1)));
            when(protocolDefinitionRepository.findById(id))
                    .thenReturn(Optional.of(buildProtocolDefinition(id)));

            service.refreshProtocolCaches();
            service.refreshProtocolCaches();

            // Same updated_at both times, so the definition is fetched and registered only once
            verify(protocolDefinitionRepository, times(1)).findById(id);
            verify(triggerMatchingService, times(1)).registerConditionOnlyTriggers(eq(id), any());
        }

        @Test
        void modifiedProtocol_isReloadedAndEvictedFromTheParsedCache() {
            UUID id = UUID.randomUUID();
            when(protocolDefinitionRepository.findFingerprintsByStatus(ProtocolDefinitionStatus.ACTIVE))
                    .thenReturn(List.of(fingerprint(id, stamp(1))))
                    .thenReturn(List.of(fingerprint(id, stamp(2))));
            when(protocolDefinitionRepository.findById(id))
                    .thenReturn(Optional.of(buildProtocolDefinition(id)));

            service.refreshProtocolCaches();
            service.refreshProtocolCaches();

            verify(protocolDefinitionRepository, times(2)).findById(id);
            verify(triggerMatchingService, times(2)).registerConditionOnlyTriggers(eq(id), any());
            verify(parsedProtocolCache, times(2)).evict(id);
        }

        @Test
        void retiredProtocol_isDroppedFromBothCaches() {
            UUID id = UUID.randomUUID();
            when(protocolDefinitionRepository.findFingerprintsByStatus(ProtocolDefinitionStatus.ACTIVE))
                    .thenReturn(List.of(fingerprint(id, stamp(1))))
                    .thenReturn(List.of());
            when(protocolDefinitionRepository.findById(id))
                    .thenReturn(Optional.of(buildProtocolDefinition(id)));

            service.refreshProtocolCaches();
            service.refreshProtocolCaches();

            // It left the ACTIVE set, so its triggers must stop firing
            verify(triggerMatchingService).removeConditionOnlyTriggers(id);
            verify(parsedProtocolCache, times(2)).evict(id);
        }

        @Test
        void noActiveProtocols_registersNothing() {
            stubActive();

            service.refreshProtocolCaches();

            verify(triggerMatchingService, never()).registerConditionOnlyTriggers(any(), any());
            verify(triggerMatchingService, never()).removeConditionOnlyTriggers(any());
        }

        @Test
        void unparseableDefinition_isSkippedAndRetriedOnTheNextSweep() {
            UUID badId = UUID.randomUUID();
            UUID goodId = UUID.randomUUID();
            ProtocolDefinition bad = buildProtocolDefinition(badId);
            bad.setDefinition(new ObjectMapper().createObjectNode().put("resourceType", "NotAPlanDefinition"));

            stubActive(fingerprint(badId, stamp(1)), fingerprint(goodId, stamp(1)));
            when(protocolDefinitionRepository.findById(badId)).thenReturn(Optional.of(bad));
            when(protocolDefinitionRepository.findById(goodId))
                    .thenReturn(Optional.of(buildProtocolDefinition(goodId)));

            assertDoesNotThrow(() -> service.refreshProtocolCaches());

            // The healthy protocol still gets its triggers — one bad row must not starve the rest
            verify(triggerMatchingService).registerConditionOnlyTriggers(eq(goodId), any());
            verify(triggerMatchingService, never()).registerConditionOnlyTriggers(eq(badId), any());

            // The bad row was not marked as cached, so the next sweep tries it again
            service.refreshProtocolCaches();
            verify(protocolDefinitionRepository, times(2)).findById(badId);
            verify(protocolDefinitionRepository, times(1)).findById(goodId);
        }

        @Test
        void definitionDeletedBetweenFingerprintAndFetch_isSkippedNotFatal() {
            UUID id = UUID.randomUUID();
            stubActive(fingerprint(id, stamp(1)));
            when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

            assertDoesNotThrow(() -> service.refreshProtocolCaches());

            verify(triggerMatchingService, never()).registerConditionOnlyTriggers(any(), any());
        }

        @Test
        void startupHook_populatesTheCaches() {
            UUID id = UUID.randomUUID();
            stubActive(fingerprint(id, stamp(1)));
            when(protocolDefinitionRepository.findById(id))
                    .thenReturn(Optional.of(buildProtocolDefinition(id)));

            service.loadProtocolCachesOnStartup();

            verify(triggerMatchingService).registerConditionOnlyTriggers(eq(id), any());
        }
    }

    // ── Helpers ──

    private void stubActive(ProtocolDefinitionRepository.Fingerprint... fingerprints) {
        when(protocolDefinitionRepository.findFingerprintsByStatus(ProtocolDefinitionStatus.ACTIVE))
                .thenReturn(List.of(fingerprints));
    }

    private static OffsetDateTime stamp(int minute) {
        return OffsetDateTime.of(2026, 8, 17, 10, minute, 0, 0, ZoneOffset.UTC);
    }

    private static ProtocolDefinitionRepository.Fingerprint fingerprint(UUID id, OffsetDateTime updatedAt) {
        return new ProtocolDefinitionRepository.Fingerprint() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public OffsetDateTime getUpdatedAt() {
                return updatedAt;
            }
        };
    }

    private ProtocolDefinition buildProtocolDefinition(UUID id) {
        try {
            return ProtocolDefinition.builder()
                    .id(id)
                    .url("http://openphc.org/PlanDefinition/anc-high-risk")
                    .version("1.0.0")
                    .status(ProtocolDefinitionStatus.ACTIVE)
                    .definition(new ObjectMapper().readTree(planDefinitionJson))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
