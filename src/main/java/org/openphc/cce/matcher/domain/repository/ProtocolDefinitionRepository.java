package org.openphc.cce.matcher.domain.repository;

import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProtocolDefinitionRepository extends JpaRepository<ProtocolDefinition, UUID> {

    Optional<ProtocolDefinition> findByUrlAndVersion(String url, String version);

    List<ProtocolDefinition> findByUrl(String url);

    List<ProtocolDefinition> findByStatus(ProtocolDefinitionStatus status);

    /**
     * Identity and last-modified stamp of each definition with the given status, without its
     * {@code definition} JSONB.
     *
     * <p>The cache refresh runs on a timer and almost always finds nothing changed, so it must not
     * drag every PlanDefinition document out of the database to discover that. This projection is what
     * it compares; only the rows that are new or whose stamp moved are then fetched in full.
     */
    @Query("SELECT p.id AS id, p.updatedAt AS updatedAt FROM ProtocolDefinition p WHERE p.status = :status")
    List<Fingerprint> findFingerprintsByStatus(@Param("status") ProtocolDefinitionStatus status);

    /** Projection for {@link #findFingerprintsByStatus}. */
    interface Fingerprint {
        UUID getId();

        OffsetDateTime getUpdatedAt();
    }
}
