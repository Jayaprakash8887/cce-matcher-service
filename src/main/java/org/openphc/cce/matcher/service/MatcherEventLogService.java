package org.openphc.cce.matcher.service;

import org.openphc.cce.matcher.domain.entity.MatcherEventLog;
import org.openphc.cce.common.enums.ProcessingStatus;
import org.openphc.cce.matcher.domain.repository.MatcherEventLogRepository;
import org.openphc.cce.common.event.CloudEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@Transactional
public class MatcherEventLogService {

    private static final Logger log = LoggerFactory.getLogger(MatcherEventLogService.class);

    private final MatcherEventLogRepository eventLogRepository;

    public MatcherEventLogService(MatcherEventLogRepository eventLogRepository) {
        this.eventLogRepository = eventLogRepository;
    }

    @Transactional(readOnly = true)
    public boolean isDuplicate(String cloudeventsId, String source) {
        return eventLogRepository.existsByCloudeventsIdAndSource(cloudeventsId, source);
    }

    public MatcherEventLog recordEvent(CloudEventMessage message, ProcessingStatus status) {
        MatcherEventLog eventLog = MatcherEventLog.builder()
                .cloudeventsId(message.getId())
                .source(message.getSource())
                .correlationId(message.getCorrelationid())
                .data(message.getData())
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .processingStatus(status)
                .build();

        eventLog = eventLogRepository.save(eventLog);

        log.debug("Recorded event: cloudeventsId={}, source={}, status={}",
                message.getId(), message.getSource(), status);

        return eventLog;
    }

    public void updateStatus(MatcherEventLog eventLog, ProcessingStatus status) {
        eventLog.setProcessingStatus(status);
        eventLogRepository.save(eventLog);

        log.debug("Updated event status: eventLogId={}, status={}",
                eventLog.getId(), status);
    }
}
