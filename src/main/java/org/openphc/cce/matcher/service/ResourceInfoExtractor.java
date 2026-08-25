package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.JsonNode;

import org.hl7.fhir.r4.model.ResourceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts resource type and coded values from FHIR resource payloads.
 * Used to prepare inputs for Tier 1 structural matching.
 */
@Component
public class ResourceInfoExtractor {

    /**
     * Extract the FHIR resource type from the event data payload.
     *
     * @param data the event payload (JsonNode representation of FHIR resource)
     * @return the {@link ResourceType}, or null if absent or not a known FHIR resource type
     * (e.g. non-FHIR {@code application/json} payloads) — such events simply match no triggers
     */
    public ResourceType extractResourceType(JsonNode data) {
        if (data == null || data.isNull()) {
            return null;
        }
        JsonNode resourceType = data.get("resourceType");
        if (resourceType == null || !resourceType.isTextual()) {
            return null;
        }
        try {
            return ResourceType.valueOf(resourceType.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Extract coded values from FHIR resource payloads for Tier 1 structural matching.
     * Handles CodeableConcept fields (object or array), and plain string fields like "status".
     *
     * <p><strong>This list is the set of paths a {@code codeFilter.path} can usefully name.</strong>
     * The Protocol Service indexes whatever path a PlanDefinition declares, so a filter on a path that
     * is not read here is indexed and then never matched — and because matching requires <em>every</em>
     * codeFilter of an action to match, one such path silently disables the whole trigger. Adding a path
     * to a protocol therefore means adding it here too.
     *
     * @param data the event payload (JsonNode representation of FHIR resource)
     * @return list of CodePathTriple with path, system, and code
     */
    public List<CodePathTriple> extractCodes(JsonNode data) {
        List<CodePathTriple> result = new ArrayList<>();
        if (data == null || data.isNull()) {
            return result;
        }

        // CodeableConcept fields (single object): code, class, serviceType, clinicalStatus,
        // verificationStatus
        extractCodingsFromPath(data, "code", result);
        extractCodingsFromPath(data, "class", result);
        extractCodingsFromPath(data, "serviceType", result);
        extractCodingsFromPath(data, "clinicalStatus", result);
        extractCodingsFromPath(data, "verificationStatus", result);

        // CodeableConcept fields (array): type[], category[]
        extractCodingsFromArrayPath(data, "type", result);
        extractCodingsFromArrayPath(data, "category", result);

        // Identifier array: identifier[] has {system, value} pairs
        extractIdentifiers(data, "identifier", result);

        // Plain string field: status (e.g. "in-progress", "active", "finished", "completed")
        extractStringField(data, "status", result);

        return result;
    }

    /**
     * Extract codings from a CodeableConcept field (single object with coding array).
     */
    private void extractCodingsFromPath(JsonNode data, String path, List<CodePathTriple> result) {
        JsonNode node = data.get(path);
        if (node == null) return;
        if (node.isObject()) {
            extractCodingsFromCodeableConcept(path, node, result);
        }
    }

    /**
     * Extract codings from an array of CodeableConcepts (e.g., type[], category[]).
     * Also falls back to single-object handling for resources where the field is 0..1.
     */
    private void extractCodingsFromArrayPath(JsonNode data, String path, List<CodePathTriple> result) {
        JsonNode node = data.get(path);
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isObject()) {
                    extractCodingsFromCodeableConcept(path, item, result);
                }
            }
        } else if (node.isObject()) {
            // Fallback: some resources define this field as 0..1 CodeableConcept
            extractCodingsFromCodeableConcept(path, node, result);
        }
    }

    private void extractCodingsFromCodeableConcept(String path, JsonNode codeableConcept,
                                                    List<CodePathTriple> result) {
        JsonNode codingList = codeableConcept.get("coding");
        if (codingList != null && codingList.isArray()) {
            for (JsonNode coding : codingList) {
                if (coding.isObject()) {
                    addCodePathTriple(path, coding, result);
                }
            }
        }
    }

    private void extractStringField(JsonNode data, String path, List<CodePathTriple> result) {
        JsonNode node = data.get(path);
        if (node != null && node.isTextual()) {
            result.add(new CodePathTriple(path, "", node.asText()));
        }
    }

    /**
     * Extract identifiers from an Identifier array (e.g., identifier[]).
     * FHIR Identifier has {system, value} — maps to CodePathTriple(path, system, value).
     */
    private void extractIdentifiers(JsonNode data, String path, List<CodePathTriple> result) {
        JsonNode node = data.get(path);
        if (node == null || !node.isArray()) return;
        for (JsonNode identifier : node) {
            if (!identifier.isObject()) continue;
            JsonNode value = identifier.get("value");
            if (value == null || !value.isTextual()) continue;
            JsonNode system = identifier.get("system");
            String systemStr = (system != null && system.isTextual()) ? system.asText() : "";
            result.add(new CodePathTriple(path, systemStr, value.asText()));
        }
    }

    private void addCodePathTriple(String path, JsonNode coding, List<CodePathTriple> result) {
        JsonNode code = coding.get("code");
        if (code == null || !code.isTextual()) {
            // Fallback: use "display" when "code" is absent (e.g., TRANSFER_ENCOUNTER)
            code = coding.get("display");
        }
        if (code != null && code.isTextual()) {
            JsonNode system = coding.get("system");
            String systemStr = (system != null && system.isTextual()) ? system.asText() : "";
            result.add(new CodePathTriple(path, systemStr, code.asText()));
        }
    }
}
