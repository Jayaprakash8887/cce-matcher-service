package org.openphc.cce.matcher.domain.repository;

import org.openphc.cce.matcher.domain.entity.MatcherEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MatcherEventLogRepository extends JpaRepository<MatcherEventLog, UUID> {

    boolean existsByCloudeventsIdAndSource(String cloudeventsId, String source);
}
