package org.openphc.cce.matcher.domain.entity;

import org.openphc.cce.common.entity.StepInstance;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only status-transition history for {@link StepInstance}.
 * One row is written by the application on each step-status/SLA-status transition
 * (creation and every subsequent change). Rows are only ever INSERTed — never UPDATEd or
 * DELETEd — so the table is a faithful, point-in-time-reconstructible record and a clean
 * CDC source.
 *
 * @see org.openphc.cce.matcher.service.StateTransitionHistoryService
 */
@Entity
@Table(name = "step_instance_history")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepInstanceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_instance_id", nullable = false)
    private UUID stepInstanceId;

    /** Recorded as-is from step_instance.step_status (already validated there). */
    @Column(name = "step_status", nullable = false)
    private String stepStatus;

    /** Recorded as-is from step_instance.sla_status (already validated there). */
    @Column(name = "sla_status", nullable = false)
    private String slaStatus;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}
