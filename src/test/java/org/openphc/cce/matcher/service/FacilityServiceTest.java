package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.matcher.domain.entity.Facility;
import org.openphc.cce.matcher.domain.repository.FacilityRepository;
import org.openphc.cce.common.event.CloudEventMessage;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FacilityServiceTest {

    @Mock
    private FacilityRepository facilityRepository;

    private FacilityService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new FacilityService(facilityRepository);
    }

    // ── ServiceRequest ─────────────────────────────────────────────────────────

    @Test
    void serviceRequest_envelopeFacilityId_savesRecord() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "NCD Upazila" }]
                }
                """;
        CloudEventMessage event = eventWith("1302", payload);
        when(facilityRepository.findByFacilityId("1302")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("1302", captor.getValue().getFacilityId());
        assertEquals("NCD Upazila", captor.getValue().getFacilityName());
        assertNull(captor.getValue().getExpectedPatientsPerDay());
    }

    @Test
    void serviceRequest_noEnvelopeFacilityId_extractsIdFromReference() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "NCD Upazila" }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("1302")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("1302", captor.getValue().getFacilityId());
        assertEquals("NCD Upazila", captor.getValue().getFacilityName());
    }

    @Test
    void serviceRequest_referenceWithOrgPrefix_stripsPrefix() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Organization/999", "display": "District Hospital" }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("999")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("999", captor.getValue().getFacilityId());
    }

    @Test
    void serviceRequest_identifierFallback_whenReferenceAbsent() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "identifier": { "value": "FAC-007" }, "display": "Health Post" }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("FAC-007")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("FAC-007", captor.getValue().getFacilityId());
        assertEquals("Health Post", captor.getValue().getFacilityName());
    }

    // ── Encounter ──────────────────────────────────────────────────────────────

    @Test
    void encounter_extractsIdAndNameFromNestedLocation() throws Exception {
        String payload = """
                {
                  "resourceType": "Encounter",
                  "location": [{
                    "location": { "reference": "Location/0030", "display": "Kacyiru Health Center" }
                  }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("0030")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("0030", captor.getValue().getFacilityId());
        assertEquals("Kacyiru Health Center", captor.getValue().getFacilityName());
    }

    @Test
    void encounter_envelopeIdTakesPrecedenceOverPayloadReference() throws Exception {
        String payload = """
                {
                  "resourceType": "Encounter",
                  "location": [{
                    "location": { "reference": "Location/0030", "display": "Kacyiru Health Center" }
                  }]
                }
                """;
        CloudEventMessage event = eventWith("ENVELOPE-ID", payload);
        when(facilityRepository.findByFacilityId("ENVELOPE-ID")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("ENVELOPE-ID", captor.getValue().getFacilityId());
        assertEquals("Kacyiru Health Center", captor.getValue().getFacilityName());
    }

    @Test
    void encounter_transfer_prefersHospitalizationOriginOverDestinationLocation() throws Exception {
        String payload = """
                {
                  "resourceType": "Encounter",
                  "hospitalization": {
                    "origin": { "reference": "Location/1651", "display": "Minazi Health Center" },
                    "destination": { "reference": "Location/0302", "display": "Ruli DH" }
                  },
                  "location": [{
                    "location": { "reference": "Location/0302", "display": "Ruli DH" }
                  }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("1651")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("1651", captor.getValue().getFacilityId());
        assertEquals("Minazi Health Center", captor.getValue().getFacilityName());
    }

    @Test
    void encounter_transfer_envelopeIdPairsWithOriginDisplayNotDestination() throws Exception {
        String payload = """
                {
                  "resourceType": "Encounter",
                  "hospitalization": {
                    "origin": { "reference": "Location/1651", "display": "Minazi Health Center" },
                    "destination": { "reference": "Location/0302", "display": "Ruli DH" }
                  },
                  "location": [{
                    "location": { "reference": "Location/0302", "display": "Ruli DH" }
                  }]
                }
                """;
        CloudEventMessage event = eventWith("1651", payload);
        when(facilityRepository.findByFacilityId("1651")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("1651", captor.getValue().getFacilityId());
        assertEquals("Minazi Health Center", captor.getValue().getFacilityName());
    }

    @Test
    void encounter_transfer_noOrigin_fallsBackToDestinationLocation() throws Exception {
        // hospitalization.origin absent — falls back to location[0], even though for a transfer
        // encounter that is the destination, not the source. The source-facility extension is
        // never consulted for Encounter, so it must NOT rescue this case.
        String payload = """
                {
                  "resourceType": "Encounter",
                  "location": [{
                    "location": { "reference": "Location/0302", "display": "Ruli DH" }
                  }],
                  "extension": [
                    { "url": "http://example.org/fhir/StructureDefinition/source-facility", "valueString": "1651" }
                  ]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("0302")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("0302", captor.getValue().getFacilityId());
        assertEquals("Ruli DH", captor.getValue().getFacilityName());
    }

    @Test
    void encounter_plain_ignoresSourceFacilityExtensionEvenWhenPresentAndDisagreeing() throws Exception {
        // Plain (non-transfer) encounter carrying a source-facility extension that disagrees with
        // location[0]: location must win — the extension is never consulted for Encounter.
        String payload = """
                {
                  "resourceType": "Encounter",
                  "location": [{
                    "location": { "reference": "Location/0030", "display": "Kacyiru Health Center" }
                  }],
                  "extension": [
                    { "url": "http://example.org/fhir/StructureDefinition/source-facility", "valueString": "9999" }
                  ]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("0030")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("0030", captor.getValue().getFacilityId());
        assertEquals("Kacyiru Health Center", captor.getValue().getFacilityName());
    }

    @Test
    void observation_noLocation_sourceFacilityExtensionCapturesFacility() throws Exception {
        // Observation has no FHIR location at all — source-facility extension is the only signal.
        String payload = """
                {
                  "resourceType": "Observation",
                  "status": "final",
                  "extension": [
                    { "url": "http://example.org/fhir/StructureDefinition/source-facility", "valueString": "0007" }
                  ]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("0007")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("0007", captor.getValue().getFacilityId());
        assertNull(captor.getValue().getFacilityName());
    }

    // ── Procedure / Immunization (direct Reference) ────────────────────────────

    @Test
    void procedure_directLocationReference_extractsIdAndName() throws Exception {
        String payload = """
                {
                  "resourceType": "Procedure",
                  "location": { "reference": "Location/0030", "display": "Outpatient Clinic" }
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("0030")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("0030", captor.getValue().getFacilityId());
        assertEquals("Outpatient Clinic", captor.getValue().getFacilityName());
    }

    @Test
    void immunization_directLocationReference_extractsIdAndName() throws Exception {
        String payload = """
                {
                  "resourceType": "Immunization",
                  "location": { "reference": "Location/0055", "display": "Vaccination Centre" }
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("0055")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("0055", captor.getValue().getFacilityId());
        assertEquals("Vaccination Centre", captor.getValue().getFacilityName());
    }

    // ── Case 1: id present, name absent → insert with null name ───────────────

    @Test
    void newFacility_noDisplayName_insertsWithNullFacilityName() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302" }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("1302")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("1302", captor.getValue().getFacilityId());
        assertNull(captor.getValue().getFacilityName());
    }

    @Test
    void newFacility_blankDisplayName_insertsWithNullFacilityName() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "   " }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityRepository.findByFacilityId("1302")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("1302", captor.getValue().getFacilityId());
        assertNull(captor.getValue().getFacilityName());
    }

    @Test
    void envelopeFacilityIdOnly_noLocationInPayload_insertsWithNullFacilityName() throws Exception {
        String payload = """
                { "resourceType": "Observation", "status": "final" }
                """;
        CloudEventMessage event = eventWith("ENV-001", payload);
        when(facilityRepository.findByFacilityId("ENV-001")).thenReturn(Optional.empty());
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(event);

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("ENV-001", captor.getValue().getFacilityId());
        assertNull(captor.getValue().getFacilityName());
    }

    // ── Case 2: existing has name, incoming has different name → update ────────

    @Test
    void existingFacility_nameChanged_updatesName() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "NCD Upazila Renamed" }]
                }
                """;
        Facility existing = Facility.builder()
                .id(UUID.randomUUID())
                .facilityId("1302")
                .facilityName("NCD Upazila")
                .build();
        when(facilityRepository.findByFacilityId("1302")).thenReturn(Optional.of(existing));
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(eventWith("1302", payload));

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("NCD Upazila Renamed", captor.getValue().getFacilityName());
    }

    @Test
    void existingFacility_nameUnchanged_doesNotSave() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "NCD Upazila" }]
                }
                """;
        Facility existing = Facility.builder()
                .id(UUID.randomUUID())
                .facilityId("1302")
                .facilityName("NCD Upazila")
                .build();
        when(facilityRepository.findByFacilityId("1302")).thenReturn(Optional.of(existing));

        service.upsertFacility(eventWith("1302", payload));

        verify(facilityRepository, never()).save(any());
    }

    // ── Case 3: existing has null name, incoming has name → update ─────────────

    @Test
    void existingFacility_nullName_incomingHasName_updatesName() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "NCD Upazila" }]
                }
                """;
        Facility existing = Facility.builder()
                .id(UUID.randomUUID())
                .facilityId("1302")
                .facilityName(null)
                .build();
        when(facilityRepository.findByFacilityId("1302")).thenReturn(Optional.of(existing));
        when(facilityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertFacility(eventWith("1302", payload));

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertEquals("NCD Upazila", captor.getValue().getFacilityName());
    }

    // ── Case 4: existing has name, incoming has no name → don't update ─────────

    @Test
    void existingFacility_hasName_incomingHasNoName_doesNotUpdate() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302" }]
                }
                """;
        Facility existing = Facility.builder()
                .id(UUID.randomUUID())
                .facilityId("1302")
                .facilityName("NCD Upazila")
                .build();
        when(facilityRepository.findByFacilityId("1302")).thenReturn(Optional.of(existing));

        service.upsertFacility(eventWith(null, payload));

        verify(facilityRepository, never()).save(any());
    }

    // ── Skip conditions ────────────────────────────────────────────────────────

    @Test
    void noFacilityIdAndNoLocationInPayload_skips() throws Exception {
        String payload = """
                { "resourceType": "Observation", "status": "final" }
                """;
        CloudEventMessage event = eventWith(null, payload);

        service.upsertFacility(event);

        verify(facilityRepository, never()).findByFacilityId(anyString());
        verify(facilityRepository, never()).save(any());
    }

    @Test
    void nullData_skips() {
        CloudEventMessage event = CloudEventMessage.builder()
                .facilityid(null)
                .data(null)
                .build();

        service.upsertFacility(event);

        verify(facilityRepository, never()).save(any());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private CloudEventMessage eventWith(String facilityId, String json) throws Exception {
        return CloudEventMessage.builder()
                .facilityid(facilityId)
                .data(mapper.readTree(json))
                .build();
    }
}
