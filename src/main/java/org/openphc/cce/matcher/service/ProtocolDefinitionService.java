package org.openphc.cce.matcher.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.openphc.cce.matcher.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read access to protocol definitions, and the keeper of the caches derived from them.
 *
 * <p>This service does not write definitions: {@code protocol_definition}, {@code trigger_index} and
 * {@code action_definition} are owned by the protocol-management service, and Matcher only reads them
 * to match events and drive follow-up.
 */
@Service
@Transactional(readOnly = true)
public class ProtocolDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDefinitionService.class);

    private final ProtocolDefinitionRepository protocolDefinitionRepository;
    private final PlanDefinitionParser planDefinitionParser;
    private final TriggerMatchingService triggerMatchingService;
    private final ParsedProtocolCache parsedProtocolCache;

    /**
     * The {@code updated_at} stamp each cached protocol was derived from, so a refresh can tell what
     * actually changed. Accessed only from the single-threaded refresh, plus the startup call.
     */
    private final Map<UUID, OffsetDateTime> cachedVersions = new HashMap<>();

    public ProtocolDefinitionService(ProtocolDefinitionRepository protocolDefinitionRepository,
                                     PlanDefinitionParser planDefinitionParser,
                                     TriggerMatchingService triggerMatchingService,
                                     ParsedProtocolCache parsedProtocolCache) {
        this.protocolDefinitionRepository = protocolDefinitionRepository;
        this.planDefinitionParser = planDefinitionParser;
        this.triggerMatchingService = triggerMatchingService;
        this.parsedProtocolCache = parsedProtocolCache;
    }

    /**
     * Bring the derived caches into line with what the protocol-management service has persisted.
     *
     * <p>Matcher holds two things it cannot rebuild from an event: the condition-only trigger set
     * (those triggers have no {@code data[]} to index, so they exist nowhere but memory) and the parsed
     * step/dependency-graph form of each definition. Neither is written here, so there is no local
     * mutation to invalidate on — hence a poll.
     *
     * <p>Polling rather than a per-entry TTL because expiry cannot discover a protocol that was never
     * cached: a newly published definition has no entry to expire, and a retired one needs removing
     * rather than refreshing. This reconciles against the full active set, so all three cases —
     * published, retired, modified — converge within one interval.
     *
     * <p>Cheap when nothing changed: only {@code (id, updated_at)} is read, and a definition's JSONB is
     * fetched and re-parsed only when its stamp moves or its id is new. Note that in-place edits are
     * detected via {@code updated_at}, so a writer that mutates {@code definition} without touching
     * that column will not be picked up; adding and retiring protocols is detected regardless, since
     * that changes which ids are active.
     *
     * <p>Runs on one thread and is safe to run on every instance independently — each keeps its own
     * in-process caches, so no coordination or leasing is needed.
     */
    @Scheduled(
            initialDelayString = "${cce.protocol.refresh-interval-ms:60000}",
            fixedDelayString = "${cce.protocol.refresh-interval-ms:60000}")
    @Transactional(readOnly = true)
    public synchronized void refreshProtocolCaches() {
        Map<UUID, OffsetDateTime> active = new HashMap<>();
        for (ProtocolDefinitionRepository.Fingerprint fingerprint :
                protocolDefinitionRepository.findFingerprintsByStatus(ProtocolDefinitionStatus.ACTIVE)) {
            active.put(fingerprint.getId(), fingerprint.getUpdatedAt());
        }

        int dropped = dropInactive(active.keySet());
        int reloaded = reloadChanged(active);

        if (dropped > 0 || reloaded > 0) {
            log.info("Protocol cache refresh: {} definition(s) loaded or reloaded, {} dropped, {} active",
                    reloaded, dropped, active.size());
        } else {
            log.debug("Protocol cache refresh: no change across {} active definition(s)", active.size());
        }
    }

    /** Initial population, ahead of the first scheduled refresh one interval later. */
    @EventListener(ApplicationReadyEvent.class)
    public void loadProtocolCachesOnStartup() {
        refreshProtocolCaches();
    }

    public ProtocolDefinition findById(UUID id) {
        return protocolDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Protocol definition not found: " + id));
    }

    /** Forget protocols that are no longer ACTIVE — retired, deleted, or otherwise withdrawn. */
    private int dropInactive(Set<UUID> activeIds) {
        Set<UUID> stale = new HashSet<>(cachedVersions.keySet());
        stale.removeAll(activeIds);

        for (UUID id : stale) {
            triggerMatchingService.removeConditionOnlyTriggers(id);
            parsedProtocolCache.evict(id);
            cachedVersions.remove(id);
            log.info("Dropped protocol {} from the derived caches — no longer active", id);
        }
        return stale.size();
    }

    /**
     * (Re)derive the caches for definitions that are new or whose {@code updated_at} moved.
     *
     * <p>A definition that fails to parse is logged and skipped rather than aborting the sweep: one bad
     * row must not cost every other protocol its triggers, and on a timer it must not fail repeatedly
     * in a way that blocks the healthy ones.
     */
    private int reloadChanged(Map<UUID, OffsetDateTime> active) {
        int reloaded = 0;
        for (Map.Entry<UUID, OffsetDateTime> entry : active.entrySet()) {
            UUID id = entry.getKey();
            if (Objects.equals(cachedVersions.get(id), entry.getValue())) {
                continue;
            }

            ProtocolDefinition protocolDef = protocolDefinitionRepository.findById(id).orElse(null);
            if (protocolDef == null) {
                continue; // deleted between the fingerprint read and now; the next sweep drops it
            }

            try {
                registerConditionOnlyTriggers(protocolDef);
                // Force the flattened steps and dependency graph to be re-derived on next use.
                parsedProtocolCache.evict(id);
                cachedVersions.put(id, entry.getValue());
                reloaded++;
            } catch (Exception e) {
                log.error("Failed to derive caches for protocol {} (id={}) — its condition-only "
                                + "triggers will not match until this is resolved",
                        protocolDef.getCanonical(), id, e);
            }
        }
        return reloaded;
    }

    /** Register one protocol's condition-only triggers, replacing whatever it had before. */
    private void registerConditionOnlyTriggers(ProtocolDefinition protocolDef) {
        List<PlanDefinitionParser.ConditionOnlyTriggerInfo> conditionOnlyInfos =
                planDefinitionParser.extractConditionOnlyTriggers(
                        planDefinitionParser.parse(protocolDef.getDefinition().toString()));

        List<ConditionOnlyTrigger> conditionOnlyTriggers = conditionOnlyInfos.stream()
                .map(info -> new ConditionOnlyTrigger(protocolDef.getId(), info.actionId(),
                        info.conditionLanguage(), info.conditionExpression()))
                .toList();

        // An empty list de-registers: the definition may have dropped its condition-only triggers.
        triggerMatchingService.registerConditionOnlyTriggers(protocolDef.getId(), conditionOnlyTriggers);
    }
}
