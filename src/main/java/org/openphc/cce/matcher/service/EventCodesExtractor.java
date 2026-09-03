package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.JsonNode;

import org.openphc.cce.common.fhir.TriggerPath;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts coded values from FHIR resource payloads. Used to prepare inputs for Tier 1 structural
 * matching, alongside the resource type, which
 * {@link org.openphc.cce.common.fhir.ResourceTypeDetector} reads — the Collector Service needs the
 * same reading, so it lives in cce-common-util rather than here.
 */
@Component
public class EventCodesExtractor {

    /**
     * Extract coded values from FHIR resource payloads for Tier 1 structural matching.
     * Handles CodeableConcept fields (object or array), and plain string fields like "status".
     *
     * <p>Driven by {@link TriggerPath}, which is also what the Protocol Service validates a
     * PlanDefinition's {@code codeFilter.path} values against. The two used to be separate lists, and a
     * path present in the protocol's but missing from this one was indexed and then never matched —
     * silently disabling the action, since matching requires every codeFilter of an action to match. One
     * enum, so a new path reaches both sides in the same change.
     *
     * @param event the event payload (JsonNode representation of FHIR resource)
     * @return list of CodePathTriple with path, system, and code
     */
    public List<CodePathTriple> extractCodes(JsonNode event) {
        List<CodePathTriple> result = new ArrayList<>();
        if (event == null || event.isNull()) {
            return result;
        }

        for (TriggerPath triggerPath : TriggerPath.values()) {
            String path = triggerPath.fhirPath();
            switch (triggerPath.shape()) {
                case CODEABLE_CONCEPT -> extractCodingsFromPath(event, path, result);
                case CODEABLE_CONCEPT_ARRAY -> extractCodingsFromArrayPath(event, path, result);
                case IDENTIFIER_ARRAY -> extractIdentifiers(event, path, result);
                case PLAIN_STRING -> extractStringField(event, path, result);
            }
        }

        return result;
    }

    /**
     * Extract codings from a CodeableConcept field (single object with coding array).
     */
    private void extractCodingsFromPath(JsonNode event, String path, List<CodePathTriple> result) {
        JsonNode node = event.get(path);
        if (node == null) return;
        if (node.isObject()) {
            extractCodingsFromCodeableConcept(path, node, result);
        }
    }

    /**
     * Extract codings from an array of CodeableConcepts (e.g., type[], category[]).
     * Also falls back to single-object handling for resources where the field is 0..1.
     */
    private void extractCodingsFromArrayPath(JsonNode event, String path, List<CodePathTriple> result) {
        JsonNode node = event.get(path);
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

    private void extractStringField(JsonNode event, String path, List<CodePathTriple> result) {
        JsonNode node = event.get(path);
        if (node != null && node.isTextual()) {
            result.add(new CodePathTriple(path, "", node.asText()));
        }
    }

    /**
     * Extract identifiers from an Identifier array (e.g., identifier[]).
     * FHIR Identifier has {system, value} — maps to CodePathTriple(path, system, value).
     */
    private void extractIdentifiers(JsonNode event, String path, List<CodePathTriple> result) {
        JsonNode node = event.get(path);
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
