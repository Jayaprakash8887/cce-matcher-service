package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceInfoExtractorTest {

    private ResourceInfoExtractor extractor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        extractor = new ResourceInfoExtractor();
    }

    private JsonNode toJsonNode(Object obj) {
        return objectMapper.valueToTree(obj);
    }

    // ── extractCodes — code.coding ──

    @Test
    void extractCodes_fromCodeCoding() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "85354-9")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals(new CodePathTriple("code", "http://loinc.org", "85354-9"), codes.get(0));
    }

    @Test
    void extractCodes_multipleCodings() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "85354-9"),
                                Map.of("system", "http://snomed.info/sct", "code", "271649006")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(2, codes.size());
        assertEquals("code", codes.get(0).path());
        assertEquals("code", codes.get(1).path());
    }

    // ── extractCodes — type (array of CodeableConcepts in FHIR Encounter) ──

    @Test
    void extractCodes_fromTypeArray() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "type", List.of(
                        Map.of("coding", List.of(
                                Map.of("system", "http://openphc.org/encounter-types",
                                        "code", "VISIT_ENCOUNTER")
                        ))
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("type", "http://openphc.org/encounter-types", "VISIT_ENCOUNTER"))));
    }

    @Test
    void extractCodes_fromTypeSingleObject() {
        // Fallback: type as single CodeableConcept (some resource types)
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "type", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://snomed.info/sct", "code", "11429006")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("type", "http://snomed.info/sct", "11429006"))));
    }

    // ── extractCodes — serviceType (Encounter.serviceType, 0..1 CodeableConcept) ──

    @Test
    void extractCodes_fromServiceType() {
        // The reference ANC protocol's enrolment trigger filters on serviceType alongside type, and
        // matching requires every codeFilter of an action to match — so a serviceType this extractor
        // did not read would silently disable that trigger rather than weaken it.
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "serviceType", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://openphc.org/service-types",
                                        "code", "high-risk-anc")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.contains(
                new CodePathTriple("serviceType", "http://openphc.org/service-types", "high-risk-anc")));
    }

    @Test
    void extractCodes_typeAndServiceTypeTogether_asTheEnrolmentTriggerNeeds() {
        // Both triples have to come out of one payload, or the AND across codeFilters can never be met.
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "type", List.of(Map.of("coding", List.of(
                        Map.of("system", "http://openphc.org/encounter-types", "code", "anc-visit")))),
                "serviceType", Map.of("coding", List.of(
                        Map.of("system", "http://openphc.org/service-types", "code", "high-risk-anc")))
        ));

        List<CodePathTriple> codes = extractor.extractCodes(data);

        assertTrue(codes.contains(
                new CodePathTriple("type", "http://openphc.org/encounter-types", "anc-visit")));
        assertTrue(codes.contains(
                new CodePathTriple("serviceType", "http://openphc.org/service-types", "high-risk-anc")));
    }

    // ── extractCodes — category[*].coding ──

    @Test
    void extractCodes_fromCategoryArrayCoding() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "category", List.of(
                        Map.of("coding", List.of(
                                Map.of("system", "http://terminology.hl7.org/CodeSystem/observation-category",
                                        "code", "vital-signs")
                        ))
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals(new CodePathTriple("category", "http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs"), codes.get(0));
    }

    @Test
    void extractCodes_multipleCategoryEntries() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "category", List.of(
                        Map.of("coding", List.of(
                                Map.of("system", "http://sys1.org", "code", "cat-1")
                        )),
                        Map.of("coding", List.of(
                                Map.of("system", "http://sys2.org", "code", "cat-2")
                        ))
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(2, codes.size());
    }

    // ── extractCodes — combined paths ──

    @Test
    void extractCodes_encounterWithCodeAndType() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://loinc.org", "code", "LP173418-7")
                        )
                ),
                "type", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://snomed.info/sct", "code", "11429006")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(2, codes.size());

        assertTrue(codes.stream().anyMatch(c -> c.path().equals("code")));
        assertTrue(codes.stream().anyMatch(c -> c.path().equals("type")));
    }

    // ── extractCodes — edge cases ──

    @Test
    void extractCodes_nullData_returnsEmptyList() {
        assertTrue(extractor.extractCodes(null).isEmpty());
    }

    @Test
    void extractCodes_emptyData_returnsEmptyList() {
        assertTrue(extractor.extractCodes(toJsonNode(Map.of())).isEmpty());
    }

    @Test
    void extractCodes_missingCode_fallsBackToDisplay() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("display", "Blood Pressure") // no system or code — falls back to display
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertEquals(1, codes.size());
        assertEquals("code", codes.get(0).path());
        assertEquals("", codes.get(0).system());
        assertEquals("Blood Pressure", codes.get(0).code());
    }

    @Test
    void extractCodes_noCodingArray_returnsEmptyList() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Observation",
                "code", Map.of("text", "BP")
        ));
        assertTrue(extractor.extractCodes(data).stream().noneMatch(c -> c.path().equals("code")));
    }

    // ── extractCodes — clinicalStatus (Condition resource) ──

    @Test
    void extractCodes_fromClinicalStatus() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Condition",
                "clinicalStatus", Map.of(
                        "coding", List.of(
                                Map.of("system", "http://terminology.hl7.org/CodeSystem/condition-clinical",
                                        "code", "active")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("clinicalStatus",
                        "http://terminology.hl7.org/CodeSystem/condition-clinical", "active"))));
    }

    // ── extractCodes — plain string status ──

    @Test
    void extractCodes_fromStringStatus() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Encounter",
                "status", "in-progress"
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("status", "", "in-progress"))));
    }

    // ── extractCodes — coding without system ──

    @Test
    void extractCodes_codingWithoutSystem() {
        JsonNode data = toJsonNode(Map.of(
                "resourceType", "Procedure",
                "code", Map.of(
                        "coding", List.of(
                                Map.of("code", "some-code")
                        )
                )
        ));
        List<CodePathTriple> codes = extractor.extractCodes(data);
        assertTrue(codes.stream().anyMatch(c ->
                c.equals(new CodePathTriple("code", "", "some-code"))));
    }

    // ── CodePathTriple.toConcatenated ──

    @Test
    void codePathTriple_toConcatenated() {
        CodePathTriple triple = new CodePathTriple("code", "http://loinc.org", "85354-9");
        assertEquals("code|http://loinc.org|85354-9", triple.toConcatenated());
    }

    @Test
    void codePathTriple_toConcatenated_emptyFields() {
        CodePathTriple triple = new CodePathTriple("", "", "");
        assertEquals("||", triple.toConcatenated());
    }

    // ── extractCodes — identifier[] ──

    @Test
    void extractCodes_identifierArray_yieldsSystemAndValue() {
        // Identifiers are how a protocol keys on an MRN or a programme enrolment number, so both the
        // namespace and the value have to survive extraction — a value alone could collide across
        // issuing systems.
        JsonNode data = objectMapper.valueToTree(Map.of(
                "resourceType", "Patient",
                "identifier", List.of(
                        Map.of("system", "http://openphc.org/mrn", "value", "260225-0002-5501"),
                        Map.of("system", "http://openphc.org/anc-reg", "value", "ANC-99"))));

        List<CodePathTriple> codes = extractor.extractCodes(data);

        assertTrue(codes.contains(new CodePathTriple("identifier", "http://openphc.org/mrn", "260225-0002-5501")));
        assertTrue(codes.contains(new CodePathTriple("identifier", "http://openphc.org/anc-reg", "ANC-99")));
    }

    @Test
    void extractCodes_identifierWithoutASystem_usesAnEmptyNamespace() {
        JsonNode data = objectMapper.valueToTree(Map.of(
                "resourceType", "Patient",
                "identifier", List.of(Map.of("value", "local-42"))));

        assertTrue(extractor.extractCodes(data)
                .contains(new CodePathTriple("identifier", "", "local-42")));
    }

    @Test
    void extractCodes_identifierWithoutAValueIsSkipped() {
        // A system with no value identifies nothing; indexing it would create a triple that can only
        // ever match another value-less payload.
        JsonNode data = objectMapper.valueToTree(Map.of(
                "resourceType", "Patient",
                "identifier", List.of(
                        Map.of("system", "http://openphc.org/mrn"),
                        Map.of("system", "http://openphc.org/mrn", "value", "keeper"))));

        List<CodePathTriple> codes = extractor.extractCodes(data);

        assertEquals(1, codes.stream().filter(c -> "identifier".equals(c.path())).count());
        assertEquals("keeper", codes.stream()
                .filter(c -> "identifier".equals(c.path())).findFirst().orElseThrow().code());
    }

    @Test
    void extractCodes_identifierThatIsNotAnArrayIsIgnored() {
        // FHIR declares identifier as a list. A single object is a malformed payload rather than
        // something to coerce, and must not abort extraction of the rest of the resource.
        JsonNode data = objectMapper.valueToTree(Map.of(
                "resourceType", "Patient",
                "identifier", Map.of("system", "s", "value", "v"),
                "status", "active"));

        List<CodePathTriple> codes = extractor.extractCodes(data);

        assertTrue(codes.stream().noneMatch(c -> "identifier".equals(c.path())));
        assertTrue(codes.contains(new CodePathTriple("status", "", "active")),
                "the rest of the resource is still extracted");
    }

    @Test
    void extractCodes_identifierEntriesThatAreNotObjectsAreSkipped() throws Exception {
        JsonNode data = objectMapper.readTree(
                "{\"resourceType\":\"Patient\",\"identifier\":[\"loose-string\",{\"value\":\"kept\"}]}");

        List<CodePathTriple> codes = extractor.extractCodes(data);

        assertEquals(List.of(new CodePathTriple("identifier", "", "kept")),
                codes.stream().filter(c -> "identifier".equals(c.path())).toList());
    }

}
