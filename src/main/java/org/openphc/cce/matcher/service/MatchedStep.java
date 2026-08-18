package org.openphc.cce.matcher.service;

import java.util.UUID;

/**
 * Result of a Tier 1 structural match — identifies a protocol definition and action
 * that matched the inbound event's resource type and codes.
 *
 * @param protocolDefinitionId the matched protocol definition UUID
 * @param actionId             the matched action ID within the protocol
 */
public record MatchedStep(UUID protocolDefinitionId, String actionId) {
}
