package org.openphc.cce.matcher.domain.repository;

import org.hl7.fhir.r4.model.ResourceType;
import org.openphc.cce.common.entity.TriggerIndex;
import org.openphc.cce.common.entity.TriggerIndexId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TriggerIndexRepository extends JpaRepository<TriggerIndex, TriggerIndexId> {

    @Query("""
            SELECT t.id.protocolDefinitionId, t.id.actionId
            FROM TriggerIndex t
            WHERE t.id.resourceType = :resourceType
              AND CONCAT(t.id.path, '|', t.id.codeSystem, '|', t.id.codeValue) IN :codeTriples
            GROUP BY t.id.protocolDefinitionId, t.id.actionId
            HAVING COUNT(DISTINCT CONCAT(t.id.path, '|', t.id.codeSystem)) = (
                SELECT COUNT(DISTINCT CONCAT(t2.id.path, '|', t2.id.codeSystem))
                FROM TriggerIndex t2
                WHERE t2.id.protocolDefinitionId = t.id.protocolDefinitionId
                  AND t2.id.actionId = t.id.actionId
                  AND t2.id.resourceType = :resourceType
            )
            """)
    List<Object[]> findStructuralMatches(
            @Param("resourceType") ResourceType resourceType,
            @Param("codeTriples") List<String> codeTriples);
}
