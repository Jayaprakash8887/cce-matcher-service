package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts coded values from FHIR resource payloads. Used to prepare inputs for Tier 1 structural
 * matching, alongside the resource type, which
 * {@link org.openphc.cce.common.fhir.ResourceTypeDetector} reads — the Collector Service needs the
 * same reading, so it lives in cce-common-util rather than here.
 */
@Component
public class EventCodesExtractor {

    /** Top-level fields that identify the resource rather than describe it; never a matching dimension. */
    private static final Set<String> IDENTITY_FIELDS = Set.of("resourceType", "id");

    /**
     * Extract coded values from every top-level field of a FHIR resource payload for Tier 1 structural
     * matching. The field's shape is inferred from the JSON itself, so a trigger on a field nobody has
     * used before needs no code change:
     *
     * <ul>
     *   <li>an object with a {@code coding} array is a CodeableConcept — each coding is read;</li>
     *   <li>an object with a {@code code} is a bare Coding (e.g. {@code Encounter.class}) — read directly;</li>
     *   <li>an array of either of those is read item by item;</li>
     *   <li>an array of objects with a {@code value} is an Identifier list (a lone object is not) — {@code system} and
     *       {@code value} are read;</li>
     *   <li>a string is a plain code with no system (e.g. {@code status}).</li>
     * </ul>
     *
     * Anything else (References, Periods, numbers, nested resources) carries no code and is skipped.
     * {@code PlanDefinitionParser.validateTriggers} accepts any single top-level field name for the
     * matching {@code codeFilter.path}, so this must read every such field rather than a fixed list.
     *
     * @param event the event payload (JsonNode representation of FHIR resource)
     * @return list of CodePathTriple with path, system, and code
     */
    public List<CodePathTriple> extractCodes(JsonNode event) {
        List<CodePathTriple> result = new ArrayList<>();
        if (event == null || !event.isObject()) {
            return result;
        }

        for (Map.Entry<String, JsonNode> field : event.properties()) {
            String path = field.getKey();
            if (IDENTITY_FIELDS.contains(path)) {
                continue;
            }
            JsonNode node = field.getValue();
            if (node.isTextual()) {
                result.add(new CodePathTriple(path, "", node.asText()));
            } else if (node.isObject()) {
                extractFromObject(path, node, false, result);
            } else if (node.isArray()) {
                for (JsonNode item : node) {
                    if (item.isObject()) {
                        extractFromObject(path, item, true, result);
                    }
                }
            }
        }

        return result;
    }

    private void extractFromObject(String path, JsonNode node, boolean arrayItem,
                                   List<CodePathTriple> result) {
        JsonNode codingList = node.get("coding");
        if (codingList != null && codingList.isArray()) {
            for (JsonNode coding : codingList) {
                if (coding.isObject()) {
                    addCodePathTriple(path, coding, result);
                }
            }
        } else if (isText(node.get("code"))) {
            addCodePathTriple(path, node, result);
        } else if (arrayItem && isText(node.get("value"))) {
            addIdentifier(path, node, result);
        }
    }

    private void addIdentifier(String path, JsonNode identifier, List<CodePathTriple> result) {
        result.add(new CodePathTriple(path, textOrEmpty(identifier.get("system")),
                identifier.get("value").asText()));
    }

    private void addCodePathTriple(String path, JsonNode coding, List<CodePathTriple> result) {
        JsonNode code = coding.get("code");
        if (!isText(code)) {
            // Fallback: use "display" when "code" is absent (e.g., TRANSFER_ENCOUNTER)
            code = coding.get("display");
        }
        if (isText(code)) {
            result.add(new CodePathTriple(path, textOrEmpty(coding.get("system")), code.asText()));
        }
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isTextual();
    }

    private static String textOrEmpty(JsonNode node) {
        return isText(node) ? node.asText() : "";
    }
}
