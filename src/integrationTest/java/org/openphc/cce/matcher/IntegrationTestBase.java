package org.openphc.cce.matcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.openphc.cce.common.entity.ActionDefinition;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.entity.TriggerIndex;
import org.openphc.cce.common.entity.TriggerIndexId;
import org.openphc.cce.common.enums.ActionDefinitionKind;
import org.openphc.cce.common.enums.ActionDefinitionStatus;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.repository.ActionDefinitionRepository;
import org.openphc.cce.matcher.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.common.repository.StepSlaStateTransitionRepository;
import org.openphc.cce.matcher.domain.repository.TriggerIndexRepository;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.matcher.service.ConditionOnlyTrigger;
import org.openphc.cce.matcher.service.TriggerMatchingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Base class for all integration tests. Uses:
 * - H2 in-memory database (PostgreSQL compatibility mode) — configured in application-integrationtest.yml
 * - EmbeddedKafka (in-process broker from spring-kafka-test) — no Docker required
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integrationtest")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "cce.events.inbound",
                "cce.intelligence.triggers",
                "cce.events.inbound.dlq"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
public abstract class IntegrationTestBase {

    @Autowired
    private ProtocolDefinitionRepository protocolDefinitionRepository;

    @Autowired
    private TriggerIndexRepository triggerIndexRepository;

    @Autowired
    private ActionDefinitionRepository actionDefinitionRepository;

    @Autowired
    private StepSlaStateTransitionRepository transitionRepository;

    @Autowired
    private TriggerMatchingService triggerMatchingService;

    @Autowired
    private PlanDefinitionParser planDefinitionParser;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Seed a protocol into the read model the way the protocol-management service would: the
     * definition row, its decomposed {@code trigger_index} entries, and its condition-only triggers.
     *
     * <p>Matcher itself never writes these — it only reads them — so tests have to stand in for the
     * service that owns them. Index construction goes through {@link PlanDefinitionParser} rather than
     * being hand-rolled here, so the fixture cannot drift from the shape Tier 1 matching expects.
     *
     * @param fhirResourcePath path to a PlanDefinition JSON under {@code src/test/resources}
     * @param version          the version to stamp, keeping each test class's protocol distinct
     * @return the persisted protocol definition id
     */
    protected UUID seedProtocol(String fhirResourcePath, String version) throws IOException {
        UUID protocolDefId = seedProtocolRows(fhirResourcePath, version);

        // Stand in for the cache refresh, so a test does not have to wait a poll interval for the
        // condition-only triggers it just seeded to become live.
        ProtocolDefinition protocolDef = protocolDefinitionRepository.findById(protocolDefId).orElseThrow();
        PlanDefinition planDefinition =
                planDefinitionParser.parse(protocolDef.getDefinition().toString());
        List<ConditionOnlyTrigger> conditionOnly = planDefinitionParser
                .extractConditionOnlyTriggers(planDefinition).stream()
                .map(info -> new ConditionOnlyTrigger(protocolDefId, info.actionId(),
                        info.conditionLanguage(), info.conditionExpression()))
                .toList();
        triggerMatchingService.registerConditionOnlyTriggers(protocolDefId, conditionOnly);

        return protocolDefId;
    }

    /**
     * Write only the rows the protocol-management service owns — {@code protocol_definition} and its
     * {@code trigger_index} entries — leaving Matcher's in-memory caches untouched.
     *
     * <p>This is the state a running Matcher is actually in the moment another service publishes a
     * protocol, so it is what the cache-refresh test starts from.
     *
     * @return the persisted protocol definition id
     */
    protected UUID seedProtocolRows(String fhirResourcePath, String version) throws IOException {
        String planDefJson = Files.readString(Path.of(fhirResourcePath))
                .replace("\"version\": \"1.0.0\"", "\"version\": \"" + version + "\"");

        PlanDefinition planDefinition = planDefinitionParser.parse(planDefJson);
        String url = planDefinition.getUrl();

        ProtocolDefinition existing = protocolDefinitionRepository
                .findByUrlAndVersion(url, version).orElse(null);
        if (existing != null) {
            return existing.getId();
        }

        JsonNode definitionNode = objectMapper.readTree(planDefJson);
        ProtocolDefinition protocolDef = protocolDefinitionRepository.save(ProtocolDefinition.builder()
                .url(url)
                .version(version)
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(definitionNode)
                .loadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());

        UUID protocolDefId = protocolDef.getId();
        // The parser yields persistence-agnostic coordinates; the service that owns trigger_index
        // maps them onto its entity. Done here as the protocol-management service would.
        List<TriggerIndex> indexEntries =
                planDefinitionParser.buildTriggerIndexEntries(planDefinition, protocolDefId).stream()
                        .map(e -> TriggerIndex.builder()
                                .id(TriggerIndexId.builder()
                                        .resourceType(e.resourceType())
                                        .path(e.path())
                                        .codeSystem(e.codeSystem())
                                        .codeValue(e.codeValue())
                                        .protocolDefinitionId(e.protocolDefinitionId())
                                        .actionId(e.actionId())
                                        .build())
                                .build())
                        .toList();
        triggerIndexRepository.saveAll(indexEntries);

        return protocolDefId;
    }

    /**
     * Seed an ActivityDefinition into {@code action_definition}, as the protocol-management service
     * would. Matcher only resolves these by canonical; it never creates them.
     *
     * @param fhirResourcePath path to an ActivityDefinition JSON
     * @return the persisted action definition id
     */
    protected UUID seedActionDefinition(String fhirResourcePath) throws IOException {
        JsonNode definition = objectMapper.readTree(Files.readString(Path.of(fhirResourcePath)));
        String url = definition.get("url").asText();
        String version = definition.get("version").asText();

        return actionDefinitionRepository.findByCanonicalUrlAndVersion(url, version)
                .orElseGet(() -> actionDefinitionRepository.save(ActionDefinition.builder()
                        .canonicalUrl(url)
                        .version(version)
                        .name(text(definition, "name"))
                        .title(text(definition, "title"))
                        .status(ActionDefinitionStatus.ACTIVE)
                        .actionType(ActionDefinitionKind.valueOf(definition.get("kind").asText()))
                        .definition(definition)
                        .build()))
                .getId();
    }

    /**
     * Schedule a step's SLA thresholds, as {@code createStep} would.
     *
     * <p>Tests that build a {@link StepInstance} directly — to stand a step up in a particular SLA
     * state — have to seed its {@code step_sla_state_transition} rows too, since the thresholds no
     * longer live on the step row. A threshold passed as null gets no row.
     *
     * @param step       an already-persisted step
     * @param dueDate    the PENDING_TO_OVERDUE threshold, or null
     * @param missedDate the OVERDUE_TO_MISSED threshold, or null
     */
    protected void seedStepSchedule(StepInstance step, OffsetDateTime dueDate, OffsetDateTime missedDate) {
        seedTransition(step, SlaTransitionType.PENDING_TO_OVERDUE, dueDate);
        seedTransition(step, SlaTransitionType.OVERDUE_TO_MISSED, missedDate);
    }

    private void seedTransition(StepInstance step, SlaTransitionType type, OffsetDateTime processBy) {
        if (processBy == null) {
            return;
        }
        transitionRepository.save(StepSlaStateTransition.builder()
                .stepInstanceId(step.getId())
                .transitionType(type)
                .fromStatus(type.fromStatus().name())
                .toStatus(type.toStatus().name())
                .processBy(processBy)
                .nextAttemptAt(processBy)
                .build());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && value.isTextual()) ? value.asText() : null;
    }
}
