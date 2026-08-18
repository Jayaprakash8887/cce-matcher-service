package org.openphc.cce.matcher.domain.repository;

import org.openphc.cce.matcher.domain.entity.ProtocolInstanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProtocolInstanceHistoryRepository extends JpaRepository<ProtocolInstanceHistory, Long> {
}
