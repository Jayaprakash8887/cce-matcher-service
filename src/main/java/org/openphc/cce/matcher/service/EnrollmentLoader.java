package org.openphc.cce.matcher.service;

import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads an enrollment for the work of one event: its parsed protocol, and its step instances under the
 * enrollment's lock.
 */
@Service
@Transactional
public class EnrollmentLoader {

    private final ProtocolInstanceService protocolInstanceService;
    private final StepInstanceRepository stepInstanceRepository;
    private final ParsedProtocolCache parsedProtocolCache;

    public EnrollmentLoader(ProtocolInstanceService protocolInstanceService,
                            StepInstanceRepository stepInstanceRepository,
                            ParsedProtocolCache parsedProtocolCache) {
        this.protocolInstanceService = protocolInstanceService;
        this.stepInstanceRepository = stepInstanceRepository;
        this.parsedProtocolCache = parsedProtocolCache;
    }

    /**
     * The enrollment, locked first when its protocol has step groups (see
     * {@link ProtocolInstanceService#lockProtocolInstance}), then its step instances read once.
     */
    public Enrollment lockAndLoad(ProtocolInstance protocolInstance) {
        ParsedProtocolCache.ParsedProtocol protocol = parsedProtocol(protocolInstance);
        protocolInstanceService.lockProtocolInstance(protocolInstance, protocol);
        return new Enrollment(protocolInstance, protocol,
                stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()));
    }

    /** The enrollment's protocol, parsed once and cached. */
    public ParsedProtocolCache.ParsedProtocol parsedProtocol(ProtocolInstance protocolInstance) {
        ProtocolDefinition protocolDef = protocolInstance.getProtocolDefinition();
        return parsedProtocolCache.get(protocolDef.getId(), () -> protocolDef.getDefinition().toString());
    }
}
