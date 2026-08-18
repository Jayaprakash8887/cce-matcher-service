package org.openphc.cce.matcher.domain.entity;

import org.openphc.cce.common.entity.ProtocolInstance;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only state-transition history for {@link ProtocolInstance}.
 * One row is written by the application on each status transition (enrollment and every
 * subsequent change). Rows are only ever INSERTed — never UPDATEd or DELETEd — so the
 * table is a faithful, point-in-time-reconstructible record and a clean CDC source.
 *
 * @see org.openphc.cce.matcher.service.StateTransitionHistoryService
 */
@Entity
@Table(name = "protocol_instance_history")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProtocolInstanceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "protocol_instance_id", nullable = false)
    private UUID protocolInstanceId;

    /** Recorded as-is from protocol_instance.status (already validated there). */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}
