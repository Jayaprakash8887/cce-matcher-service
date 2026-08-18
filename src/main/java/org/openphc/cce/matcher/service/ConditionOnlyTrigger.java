package org.openphc.cce.matcher.service;

import java.util.UUID;

/**
 * In-memory representation of a trigger with no data[] section (only a condition).
 * These are evaluated via Tier 2 for every inbound event.
 *
 * @param protocolDefinitionId the protocol definition UUID
 * @param actionId             the action ID within the protocol
 * @param conditionLanguage    the expression language (e.g., "text/jsonlogic", "text/fhirpath")
 * @param conditionExpression  the expression string
 */
public record ConditionOnlyTrigger(
        UUID protocolDefinitionId,
        String actionId,
        String conditionLanguage,
        String conditionExpression) {
}
