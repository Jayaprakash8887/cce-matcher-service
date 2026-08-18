package org.openphc.cce.matcher.service;

/**
 * Represents a code extracted from a FHIR resource for Tier 1 matching.
 * Each triple corresponds to a codeFilter entry in the trigger index.
 *
 * @param path   the FHIR path where the code was found (e.g., "code", "type", "category")
 * @param system the code system URI
 * @param code   the code value
 */
public record CodePathTriple(String path, String system, String code) {

    /**
     * Returns the concatenated form used by the JPQL query: "path|system|code"
     */
    public String toConcatenated() {
        return path + "|" + system + "|" + code;
    }
}
