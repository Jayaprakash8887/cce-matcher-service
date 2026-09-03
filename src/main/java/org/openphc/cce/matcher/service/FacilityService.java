package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.matcher.domain.entity.Facility;
import org.openphc.cce.matcher.domain.repository.FacilityRepository;
import org.openphc.cce.common.event.CloudEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class FacilityService {

    private static final Logger log = LoggerFactory.getLogger(FacilityService.class);

    private final FacilityRepository facilityRepository;

    public FacilityService(FacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    @Transactional
    public void upsertFacility(CloudEventMessage event) {
        FacilityDetails facilityDetails = extractFacilityDetails(event);
        if (facilityDetails == null) return;

        Optional<Facility> optExistingFacility = facilityRepository.findByFacilityId(facilityDetails.id());
        if (optExistingFacility.isPresent()) {
            Facility existingFacility = optExistingFacility.get();
            // Update only when incoming name is present AND differs from stored name (covers null→name and name→newName).
            // If incoming name is absent, keep whatever is stored (avoids overwriting a known name with null).
            if (facilityDetails.name() != null && !facilityDetails.name().equals(existingFacility.getFacilityName())) {
                existingFacility.setFacilityName(facilityDetails.name());
                facilityRepository.save(existingFacility);
                log.info("Updated facility name: facilityId={}, name={}", facilityDetails.id(), facilityDetails.name());
            }
            return;
        }

        facilityRepository.save(
                Facility.builder()
                        .facilityId(facilityDetails.id())
                        .facilityName(facilityDetails.name())
                        .build()
        );
        log.info("Captured new facility: facilityId={}, name={}", facilityDetails.id(), facilityDetails.name());
    }

    /**
     * Single-pass extraction of facility ID and display name from the FHIR payload.
     * Both values sit on the same Reference node, so one walk covers both.
     *
     * ID: CloudEvent envelope facilityid (preferred) → reference string (strip prefix) → identifier.value
     * Name: display field on the same Reference node
     *
     * Resource paths (mirrors FacilityIdExtractor in openhim-cce-emitter-adaptor):
     *   ServiceRequest : locationReference[0]
     *   Encounter      : hospitalization.origin → location[0].location (never the source-facility
     *                     extension — see below)
     *   Procedure /
     *   Immunization   : location (direct Reference) → source-facility extension
     *   any other type : source-facility extension (e.g. Observation, Condition, MedicationRequest,
     *                     which carry no FHIR location at all)
     *
     * Per FHIR R4 (https://hl7.org/fhir/R4/encounter.html), {@code hospitalization} is only ever
     * populated on a transfer Encounter — plain visit/consultation encounters never carry it. For a
     * transfer Encounter, location[0].location reflects where the patient ended up (the
     * destination), not where the encounter/referral originated, so hospitalization.origin is
     * checked first and is the correct source facility; location[0] is the fallback for the
     * non-transfer encounter types that have no hospitalization at all. The source-facility
     * extension is deliberately never consulted for Encounter (superseded by reading
     * hospitalization.origin directly).
     */
    private FacilityDetails extractFacilityDetails(CloudEventMessage event) {
        String facilityId = event.getFacilityid();
        JsonNode eventData = event.getData();
        if (eventData == null && (facilityId == null || facilityId.isBlank())) return null;

        // ServiceRequest: locationReference[0]
        JsonNode locationRef = eventData != null ? eventData.get("locationReference") : null;
        if (locationRef != null && locationRef.isArray()) {
            for (JsonNode locationRefEntry : locationRef) {
                FacilityDetails facilityDetails = fromRefNode(locationRefEntry, facilityId);
                if (facilityDetails != null) return facilityDetails;
            }
        }

        // Encounter (transfer): hospitalization.origin takes priority over location[0].location
        JsonNode hospitalization = eventData != null ? eventData.get("hospitalization") : null;
        JsonNode originRef = hospitalization != null ? hospitalization.get("origin") : null;
        if (originRef != null) {
            FacilityDetails facilityDetails = fromRefNode(originRef, facilityId);
            if (facilityDetails != null) return facilityDetails;
        }

        JsonNode locationNode = eventData != null ? eventData.get("location") : null;
        if (locationNode != null) {
            if (locationNode.isArray()) {
                // Encounter: location[0].location — the fallback for non-transfer encounters
                // (visit/consultation), which never carry hospitalization.origin at all.
                for (JsonNode locationEntry : locationNode) {
                    JsonNode nestedLocationRef = locationEntry.get("location");
                    if (nestedLocationRef != null) {
                        FacilityDetails facilityDetails = fromRefNode(nestedLocationRef, facilityId);
                        if (facilityDetails != null) return facilityDetails;
                    }
                }
            } else {
                // Procedure / Immunization: location (direct Reference)
                FacilityDetails facilityDetails = fromRefNode(locationNode, facilityId);
                if (facilityDetails != null) return facilityDetails;
            }
        }

        // Fallback: envelope facilityId, or else the source-facility extension. Still needed here
        // because Observation/Condition/MedicationRequest/... carry no FHIR location field at all —
        // the extension is their only signal. An Encounter never reaches this point, having already
        // returned from hospitalization.origin or location[0] above; that is deliberate, because the
        // extension cannot distinguish a TRANSFER_ENCOUNTER's origin from its destination. No display
        // name is available at this point.
        String resolvedId = facilityId != null && !facilityId.isBlank() ? facilityId : extractSourceFacilityExtension(eventData);
        return resolvedId != null ? new FacilityDetails(resolvedId, null) : null;
    }

    /**
     * Extracts the facility ID from the source system's {@code source-facility} extension
     * (matched by URL suffix so it survives base-URL changes), e.g.:
     * {@code {"url": ".../source-facility", "valueString": "1651"}} → {@code "1651"}.
     */
    private String extractSourceFacilityExtension(JsonNode eventData) {
        JsonNode extensions = eventData != null ? eventData.get("extension") : null;
        if (extensions == null || !extensions.isArray()) return null;
        for (JsonNode extension : extensions) {
            String url = textOrNull(extension.get("url"));
            if (url != null && url.endsWith("source-facility")) {
                String value = textOrNull(extension.get("valueString"));
                if (value != null) return value;
            }
        }
        return null;
    }

    /**
     * Extracts facilityId and facilityName from a single FHIR Reference node in one pass.
     * facilityId resolution order: envelope facilityId → reference string (strip ResourceType/ prefix) → identifier.value
     * facilityName is taken from the display field and may be null — a row with a known id but unknown name is still persisted.
     * Returns null only when facilityId cannot be resolved from any source.
     */
    private FacilityDetails fromRefNode(JsonNode locationRefNode, String facilityId) {
        // ID: envelope first, then reference string (strip prefix), then identifier.value
        if (facilityId == null || facilityId.isBlank()) {
            String reference = textOrNull(locationRefNode.get("reference"));
            if (reference != null) {
                facilityId = reference.contains("/") ? reference.substring(reference.lastIndexOf('/') + 1) : reference;
            }
        }
        if (facilityId == null || facilityId.isBlank()) {
            JsonNode identifier = locationRefNode.get("identifier");
            if (identifier != null) facilityId = textOrNull(identifier.get("value"));
        }

        if (facilityId == null || facilityId.isBlank()) return null;

        String facilityName = textOrNull(locationRefNode.get("display"));
        return new FacilityDetails(facilityId, facilityName);
    }

    private record FacilityDetails(String id, String name) {}

    private String textOrNull(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) return null;
        String text = jsonNode.asText().strip();
        return text.isBlank() ? null : text;
    }
}
