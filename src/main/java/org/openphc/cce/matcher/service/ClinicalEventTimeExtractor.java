package org.openphc.cce.matcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Extracts the <em>clinical occurrence time</em> from a FHIR resource payload — i.e. when the
 * clinical act actually happened, as opposed to the CloudEvent envelope {@code time} (which
 * carries the emitter-adaptor's transmission clock) or the moment Matcher processed the event.
 *
 * <p>Used to base a completed step's {@code completedAt} — and therefore the due/overdue/missed
 * dates of dependent steps — on real-world clinical time, so ingestion lag (offline sync, batch
 * upload, retries, DLQ replay) does not shift downstream schedules or deviation timing.
 *
 * <p>FHIR has no single "when did this happen" field: each resource type carries its own, and most
 * are polymorphic choice types ({@code effective[x]}, {@code performed[x]}, {@code occurrence[x]}).
 * The {@link #CANDIDATES} table maps each supported resource type to an ordered list of concrete
 * JSON fields to probe — the first that resolves wins. Because FHIR JSON encodes the choice type in
 * the field name, this is a plain field-name lookup rather than type introspection.
 *
 * <p>The extractor is intentionally best-effort: an unmapped resource type, a missing field, or an
 * unparseable value returns {@code null}, and the caller falls back to the envelope time. This means
 * the change can only ever <em>improve</em> accuracy where a clinical field is present, never regress.
 */
@Component
public class ClinicalEventTimeExtractor {

    private static final Logger log = LoggerFactory.getLogger(ClinicalEventTimeExtractor.class);

    /**
     * Ordered clinical-time candidates per FHIR resource type. The first candidate that resolves to
     * a parseable value wins. A {@code Period}'s {@code end} bound reflects "when it finished"
     * semantics rather than when the clinical act actually occurred, so it is always the last-resort
     * candidate in each type's list — tried only after every other field (including that same
     * Period's {@code start} bound, where present) has failed to resolve.
     */
    private static final Map<ResourceType, List<TimeCandidate>> CANDIDATES = Map.ofEntries(
            Map.entry(ResourceType.Observation, List.of(
                    field("effectiveDateTime"), field("effectiveInstant"),
                    periodStart("effectivePeriod"), field("issued"), periodEnd("effectivePeriod"))),
            Map.entry(ResourceType.Encounter, List.of(
                    periodStart("period"), periodEnd("period"))),
            Map.entry(ResourceType.Procedure, List.of(
                    field("performedDateTime"), periodStart("performedPeriod"), periodEnd("performedPeriod"))),
            Map.entry(ResourceType.Immunization, List.of(
                    field("occurrenceDateTime"))),
            Map.entry(ResourceType.MedicationAdministration, List.of(
                    field("effectiveDateTime"), periodStart("effectivePeriod"), periodEnd("effectivePeriod"))),
            Map.entry(ResourceType.MedicationDispense, List.of(
                    field("whenHandedOver"), field("whenPrepared"))),
            Map.entry(ResourceType.MedicationRequest, List.of(
                    field("authoredOn"))),
            Map.entry(ResourceType.Condition, List.of(
                    field("onsetDateTime"), periodStart("onsetPeriod"), field("recordedDate"))),
            Map.entry(ResourceType.AllergyIntolerance, List.of(
                    field("onsetDateTime"), field("recordedDate"), field("lastOccurrence"))),
            Map.entry(ResourceType.ServiceRequest, List.of(
                    field("occurrenceDateTime"), field("authoredOn"), periodEnd("occurrencePeriod"))),
            Map.entry(ResourceType.Consent, List.of(
                    field("dateTime"))),
            Map.entry(ResourceType.DiagnosticReport, List.of(
                    field("effectiveDateTime"), field("issued"), periodEnd("effectivePeriod")))
    );

    private final MeterRegistry meterRegistry;

    public ClinicalEventTimeExtractor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Best-effort clinical occurrence time from a FHIR payload.
     *
     * @param resourceType the FHIR resource type (e.g. {@code "Observation"}); may be null
     * @param data         the FHIR resource payload; may be null
     * @return the clinical occurrence time, or {@code null} if it cannot be derived (unmapped type,
     * missing field, or unparseable value) — the caller should then fall back to the envelope time
     */
    public OffsetDateTime extract(ResourceType resourceType, JsonNode data) {
        if (resourceType == null || data == null || data.isNull()) {
            return null;
        }
        List<TimeCandidate> candidates = CANDIDATES.get(resourceType);
        if (candidates == null) {
            Counter.builder("cce.clinical_time.unmapped")
                    .description("Events whose resource type has no clinical-time mapping")
                    .tag("resourceType", resourceType.toString())
                    .register(meterRegistry).increment();
            log.debug("No clinical-time mapping for resourceType={} — falling back to envelope time", resourceType);
            return null;
        }
        for (TimeCandidate candidate : candidates) {
            OffsetDateTime resolved = candidate.resolve(data, raw -> parseFhirDate(resourceType, raw));
            if (resolved != null) {
                return resolved;
            }
        }
        log.debug("No clinical-time field present for resourceType={} — falling back to envelope time", resourceType);
        return null;
    }

    /**
     * Lenient FHIR date/dateTime/instant parsing via HAPI, which handles partial precision
     * ({@code 2026}, {@code 2026-03}, {@code 2026-03-15}) as well as full timestamps with offsets.
     * Returns {@code null} (and meters) for unparseable values rather than throwing.
     */
    private OffsetDateTime parseFhirDate(ResourceType resourceType, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Date value = new DateTimeType(raw).getValue();
            return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            Counter.builder("cce.clinical_time.unparseable")
                    .description("Events carrying a clinical-time value that could not be parsed")
                    .tag("resourceType", resourceType.toString())
                    .register(meterRegistry).increment();
            log.warn("Unparseable clinical date '{}' on resourceType={} — falling back", raw, resourceType);
            return null;
        }
    }

    private static TimeCandidate field(String name) {
        return new TimeCandidate(name, null);
    }

    private static TimeCandidate periodEnd(String name) {
        return new TimeCandidate(name, "end");
    }

    private static TimeCandidate periodStart(String name) {
        return new TimeCandidate(name, "start");
    }

    /**
     * A single candidate location for a clinical time: a top-level {@code field}, optionally a
     * {@code bound} ("end"/"start") when that field is a {@code Period}.
     */
    private record TimeCandidate(String field, String bound) {
        OffsetDateTime resolve(JsonNode data, Function<String, OffsetDateTime> parser) {
            JsonNode node = data.get(field);
            if (node == null || node.isNull()) {
                return null;
            }
            if (bound == null) {
                return node.isTextual() ? parser.apply(node.asText()) : null;
            }
            JsonNode boundNode = node.get(bound);
            return (boundNode != null && boundNode.isTextual()) ? parser.apply(boundNode.asText()) : null;
        }
    }
}
