package org.openphc.cce.matcher.service;

import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.hl7.fhir.r4.model.ResourceType;
import org.openphc.cce.matcher.domain.entity.MatcherEventLog;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.ProcessingStatus;
import org.openphc.cce.common.fhir.FhirExpressionEvaluator;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.fhir.ResourceTypeDetector;
import org.openphc.cce.common.event.CloudEventMessage;
import org.openphc.cce.common.fhir.ClinicalEventTimeExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Central orchestrator through which all inbound event processing flows.
 * Wires together idempotency, resource extraction, two-tier matching,
 * enrollment, step completion, progressive instantiation, and deviation recording.
 */
@Service
@Transactional
public class MatcherEngine {

    private static final Logger log = LoggerFactory.getLogger(MatcherEngine.class);

    private final MatcherEventLogService eventLogService;
    private final ResourceTypeDetector resourceTypeDetector;
    private final EventCodesExtractor eventCodesExtractor;
    private final TriggerMatchingService triggerMatchingService;
    private final FhirExpressionEvaluator fhirExpressionEvaluator;
    private final ProtocolDefinitionService protocolDefinitionService;
    private final ProtocolInstanceService protocolInstanceService;
    private final StepInstanceService stepInstanceService;
    private final StepCreationService stepCreationService;
    private final ParsedProtocolCache parsedProtocolCache;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;
    private final ClinicalEventTimeExtractor clinicalEventTimeExtractor;

    private final Counter eventsProcessedCounter;
    private final Counter eventsMatchedCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsZeroMatchCounter;
    private final Timer matchingDurationTimer;
    private final Timer eventProcessingTimer;

    public MatcherEngine(MatcherEventLogService eventLogService,
                            ResourceTypeDetector resourceTypeDetector,
                            EventCodesExtractor eventCodesExtractor,
                            TriggerMatchingService triggerMatchingService,
                            FhirExpressionEvaluator fhirExpressionEvaluator,
                            ProtocolDefinitionService protocolDefinitionService,
                            ProtocolInstanceService protocolInstanceService,
                            StepInstanceService stepInstanceService,
                            StepCreationService stepCreationService,
                            ParsedProtocolCache parsedProtocolCache,
                            IntelligenceActionEvaluator intelligenceActionEvaluator,
                            ClinicalEventTimeExtractor clinicalEventTimeExtractor,
                            MeterRegistry meterRegistry) {
        this.eventLogService = eventLogService;
        this.resourceTypeDetector = resourceTypeDetector;
        this.eventCodesExtractor = eventCodesExtractor;
        this.triggerMatchingService = triggerMatchingService;
        this.fhirExpressionEvaluator = fhirExpressionEvaluator;
        this.protocolDefinitionService = protocolDefinitionService;
        this.protocolInstanceService = protocolInstanceService;
        this.stepInstanceService = stepInstanceService;
        this.stepCreationService = stepCreationService;
        this.parsedProtocolCache = parsedProtocolCache;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.clinicalEventTimeExtractor = clinicalEventTimeExtractor;

        this.eventsProcessedCounter = Counter.builder("cce.events.processed")
                .description("Total inbound events processed")
                .register(meterRegistry);
        this.eventsMatchedCounter = Counter.builder("cce.events.matched")
                .tag("status", "matched")
                .description("Events that matched at least one trigger")
                .register(meterRegistry);
        this.eventsDuplicateCounter = Counter.builder("cce.events.duplicate")
                .description("Duplicate events skipped via idempotency check")
                .register(meterRegistry);
        this.eventsZeroMatchCounter = Counter.builder("cce.events.matched")
                .tag("status", "zero_match")
                .description("Events that matched no triggers")
                .register(meterRegistry);
        this.matchingDurationTimer = Timer.builder("cce.step.matching.duration")
                .description("Time taken for trigger matching pipeline")
                .register(meterRegistry);
        this.eventProcessingTimer = Timer.builder("cce.events.processing.duration")
                .description("Total time taken to process an inbound event end-to-end")
                .register(meterRegistry);
    }

    /**
     * Main entry point for all inbound clinical events.
     */
    public void processInboundEvent(CloudEventMessage event) {
        eventProcessingTimer.record(() -> doProcessInboundEvent(event));
    }

    private void doProcessInboundEvent(CloudEventMessage event) {
        eventsProcessedCounter.increment();

        // A redelivered or resent event is already in the log under the same (cloudevents_id, source),
        // which is unique, so a second row cannot be written: inserting one threw, the listener retried
        // the same insert, and the event ended up dead-lettered. The first row is the record of the
        // event; a repeat is only counted.
        if (eventLogService.isDuplicate(event.getId(), event.getSource())) {
            log.info("Duplicate event detected: cloudeventsId={}, source={}", event.getId(), event.getSource());
            eventsDuplicateCounter.increment();
            return;
        }

        MatcherEventLog eventLog = eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH);

        // An explicit actionid extension bypasses both matching tiers, so short-circuit before extracting —
        // walking the payload for codes would be wasted work on this path.
        if (event.getActionid() != null && !event.getActionid().isBlank()) {
            processExplicitMatch(event, eventLog);
            return;
        }

        JsonNode eventData = event.getData();
        ResourceType resourceType = resourceTypeDetector.detect(eventData);
        List<CodePathTriple> codes = eventCodesExtractor.extractCodes(eventData);

        List<MatchedStep> finalMatches = matchingDurationTimer.record(() ->
                performTwoTierMatching(resourceType, codes, eventData));

        if (finalMatches != null && !finalMatches.isEmpty()) {
            for (MatchedStep match : finalMatches) {
                processMatch(match, event, eventLog);
            }
            eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);
            eventsMatchedCounter.increment();

            log.info("Event processing complete: cloudeventsId={}, matches={}",
                    event.getId(), finalMatches.size());
        } else {
            eventsZeroMatchCounter.increment();
            log.info("No matches for event: cloudeventsId={}, resourceType={}",
                    event.getId(), resourceType);
        }
    }

    /**
     * Process an explicit match — bypass Tier 1/2 entirely.
     * Uses the actionid and protocolinstanceid CloudEvent extensions.
     */
    void processExplicitMatch(CloudEventMessage event, MatcherEventLog eventLog) {
        String stepActionId = event.getActionid();
        String protocolInstanceIdStr = event.getProtocolinstanceid();

        if (protocolInstanceIdStr == null || protocolInstanceIdStr.isBlank()) {
            log.warn("Explicit match requested but protocolInstanceId is missing: cloudeventsId={}",
                    event.getId());
            return;
        }

        UUID protocolInstanceId = UUID.fromString(protocolInstanceIdStr);
        ProtocolInstance protocolInstance = protocolInstanceService.findById(protocolInstanceId);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime occurredAt = notAfter(resolveOccurredAt(event), now);
        StepInstance step = stepForEvent(protocolInstance, stepActionId, now, occurredAt);

        stepInstanceService.completeStep(step, eventLog.getId(), event.getSource(), occurredAt);

        // Evaluate intelligence actions after step completion
        intelligenceActionEvaluator.evaluateOnCompletion(step, event.getData());

        eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);
        eventsMatchedCounter.increment();

        log.info("Explicit match processed: cloudeventsId={}, actionId={}, protocolInstanceId={}",
                event.getId(), stepActionId, protocolInstanceId);
    }

    private List<MatchedStep> performTwoTierMatching(ResourceType resourceType, List<CodePathTriple> codes,
                                                       JsonNode eventData) {
        List<MatchedStep> finalMatches = new ArrayList<>();

        List<MatchedStep> tier1Matches = triggerMatchingService.findStructuralMatches(resourceType, codes);
        List<ConditionOnlyTrigger> conditionOnlyTriggers = triggerMatchingService.getConditionOnlyTriggers();

        // Tier 2: a Tier 1 structural match still has to satisfy its trigger condition, if it has one
        for (MatchedStep match : tier1Matches) {
            PlanDefinitionParser.StepMetadata stepMetadata =
                    stepMetadata(match.protocolDefinitionId(), match.stepActionId());
            if (stepMetadata == null) {
                continue;
            }

            boolean hasCondition = stepMetadata.triggers().stream()
                    .anyMatch(trigger -> trigger.condition() != null);

            if (!hasCondition || evaluateTriggerConditions(stepMetadata, eventData)) {
                finalMatches.add(match);
            }
        }

        // Triggers with a condition and no data[] are matched on the condition alone
        for (ConditionOnlyTrigger trigger : conditionOnlyTriggers) {
            boolean result = fhirExpressionEvaluator.evaluate(
                    trigger.conditionLanguage(), trigger.conditionExpression(), eventData);
            if (result) {
                finalMatches.add(new MatchedStep(trigger.protocolDefinitionId(), trigger.stepActionId()));
            }
        }

        return finalMatches;
    }

    private boolean evaluateTriggerConditions(PlanDefinitionParser.StepMetadata stepMetadata,
                                               JsonNode eventData) {
        for (PlanDefinitionParser.TriggerInfo trigger : stepMetadata.triggers()) {
            if (trigger.condition() != null) {
                boolean result = fhirExpressionEvaluator.evaluate(
                        trigger.condition().language(),
                        trigger.condition().expression(),
                        eventData);
                if (result) {
                    return true;
                }
            }
        }
        return false;
    }

    private void processMatch(MatchedStep match, CloudEventMessage event, MatcherEventLog eventLog) {
        ProtocolDefinition protocolDef = protocolDefinitionService.findById(match.protocolDefinitionId());
        String patientId = event.getSubject();

        // Both enrolment and completion are stamped with the CLINICAL occurrence time, NOT the
        // processing clock, so enrolled_at and completed_at reflect when the patient actually
        // entered care and when the act actually happened — unaffected by ingestion lag (offline
        // sync, batch, DLQ replay). resolveOccurredAt falls back to the envelope time, then now().
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime occurredAt = notAfter(resolveOccurredAt(event), now);

        // Idempotent — returns the existing instance if the patient is already enrolled
        ProtocolInstance protocolInstance = protocolInstanceService.enrollPatient(
                patientId, protocolDef, occurredAt);

        String stepActionId = match.stepActionId();

        StepInstance step = stepForEvent(protocolInstance, stepActionId, now, occurredAt);

        stepInstanceService.completeStep(step, eventLog.getId(), event.getSource(), occurredAt);

        // Evaluate intelligence actions after step completion
        intelligenceActionEvaluator.evaluateOnCompletion(step, event.getData());

    }

    /**
     * Resolve the clinical occurrence time to attribute a completion to. Precedence:
     * <ol>
     *   <li>Clinical time extracted from the FHIR payload (when the datacontenttype is FHIR and a
     *       clinical field is present) — the real-world time the act happened;</li>
     *   <li>the CloudEvent envelope {@code time} — the emitter-adaptor's transmission clock, which
     *       is stable across retries/DLQ replay and closer to the event than our processing time;</li>
     *   <li>{@code now()} — defensive last resort (the envelope time is expected to always be present).</li>
     * </ol>
     * Non-FHIR ({@code application/json}) payloads skip extraction and use the envelope time directly.
     * Callers clamp the result with {@link #notAfter}, so a bad/future source clock cannot push
     * schedules out.
     */
    private OffsetDateTime resolveOccurredAt(CloudEventMessage event) {
        if (isFhir(event)) {
            ResourceType resourceType = resourceTypeDetector.detect(event.getData());
            OffsetDateTime clinical = clinicalEventTimeExtractor.extract(resourceType, event.getData());
            if (clinical != null) {
                return clinical;
            }
        }
        return event.getTime() != null ? event.getTime() : OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Whether the payload is FHIR (and thus a candidate for clinical-time extraction). The Collector
     * defaults to {@code application/fhir+json}, so a null/absent content type is treated as FHIR.
     */
    private boolean isFhir(CloudEventMessage event) {
        String contentType = event.getDatacontenttype();
        return contentType == null || contentType.toLowerCase().contains("fhir");
    }

    /**
     * The occurrence time, or {@code now} when it is later: work cannot have happened in the future.
     *
     * <p>{@code now} must be the same instant the event's {@link #createInitialStep} uses as the new
     * step's due date — one reading of the clock per event. A source whose clock runs ahead, or that
     * labels local time as UTC, sends a future occurrence time. Clamped to a second reading taken a
     * moment after the due date, the completion of the very step the event created landed microseconds
     * after its deadline: no {@code MET_CONDITION_REACHED} row, and an {@code OVERDUE} deviation for
     * on-time work. Clamped to the same instant, it lands on the due date, which counts as on time.
     */
    private static OffsetDateTime notAfter(OffsetDateTime occurredAt, OffsetDateTime now) {
        return occurredAt.isAfter(now) ? now : occurredAt;
    }

    /**
     * The row an event for {@code stepActionId} completes, created when none is awaiting it. A step the event
     * itself brings into being is due at {@code now}, the event's single clock reading.
     *
     * <p>A step in a step group is left to {@link StepInstanceService#findOrCreateGroupedStep}, which
     * first decides, under the enrollment's lock, which instance of the group the event goes to.
     * Outside any group, the open row with the nearest due date is completed, or a new one created.
     */
    private StepInstance stepForEvent(ProtocolInstance protocolInstance, String stepActionId,
                                      OffsetDateTime now, OffsetDateTime occurredAt) {
        PlanDefinitionParser.StepMetadata stepMetadata = parsedProtocolCache
                .get(protocolInstance.getProtocolDefinition().getId(),
                        () -> protocolInstance.getProtocolDefinition().getDefinition().toString())
                .step(stepActionId);

        if (stepMetadata != null && stepMetadata.groupId() != null) {
            return stepInstanceService.findOrCreateGroupedStep(protocolInstance, stepMetadata, now, occurredAt);
        }

        StepInstance actionableStep =
                stepInstanceService.findActionableStep(protocolInstance.getId(), stepActionId, occurredAt);
        if (actionableStep != null) {
            return actionableStep;
        }
        if (stepMetadata == null) {
            // An explicit match can name an action the protocol doesn't declare: a bare row, with no deadline.
            return stepCreationService.createStep(protocolInstance, stepActionId, 0, now, null, null);
        }
        return createInitialStep(protocolInstance, stepMetadata, now);
    }

    /** A step outside any group that the event itself brings into being: due at {@code now}. */
    private StepInstance createInitialStep(ProtocolInstance protocolInstance,
                                           PlanDefinitionParser.StepMetadata stepMetadata, OffsetDateTime now) {
        // tolerance-days is the single grace window: missedDate = dueDate + tolerance.
        OffsetDateTime missedDate = stepMetadata.toleranceDays() != null
                ? now.plusDays(stepMetadata.toleranceDays())
                : null;
        return stepCreationService.createStep(protocolInstance, stepMetadata.id(), 0,
                now, missedDate, stepMetadata.requiredBehavior());
    }

    /** A matched action's metadata, or null when the protocol declares no such action. */
    private PlanDefinitionParser.StepMetadata stepMetadata(UUID protocolDefId, String stepActionId) {
        return parsedProtocolCache.get(protocolDefId,
                () -> protocolDefinitionService.findById(protocolDefId).getDefinition().toString()).step(stepActionId);
    }
}
