package org.openphc.cce.matcher.service;

import org.openphc.cce.common.intelligence.ActionDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.ActionDefinition;
import org.openphc.cce.common.enums.ActionDefinitionKind;
import org.openphc.cce.common.enums.ActionDefinitionStatus;
import org.openphc.cce.common.repository.ActionDefinitionRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Matcher only resolves action definitions by canonical reference; creating and retiring them belongs
 * to the protocol-management service.
 */
@ExtendWith(MockitoExtension.class)
class ActionDefinitionServiceTest {

    @Mock
    private ActionDefinitionRepository actionDefinitionRepository;

    private ActionDefinitionResolver service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ActionDefinitionResolver(actionDefinitionRepository);
    }

    @Test
    void canonicalResolves_splitsUrlAndVersion() {
        ActionDefinition actionDef = buildActionDef(UUID.randomUUID());
        when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                "http://openphc.org/ActivityDefinition/test", "1.0"))
                .thenReturn(Optional.of(actionDef));

        ActionDefinition result = service.resolveByCanonical(
                "http://openphc.org/ActivityDefinition/test|1.0");

        assertSame(actionDef, result);
    }

    @Test
    void canonicalNotFound_throwsEntityNotFound() {
        when(actionDefinitionRepository.findByCanonicalUrlAndVersion(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.resolveByCanonical("http://openphc.org/unknown|1.0"));
    }

    @Test
    void invalidCanonicalFormat_noPipe_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveByCanonical("http://openphc.org/test"));
    }

    @Test
    void invalidCanonicalFormat_endsWithPipe_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveByCanonical("http://openphc.org/test|"));
    }

    @Test
    void invalidCanonicalFormat_startsWithPipe_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveByCanonical("|1.0"));
    }

    @Test
    void canonicalWithMultiplePipes_usesLastSeparator() {
        ActionDefinition actionDef = buildActionDef(UUID.randomUUID());
        when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                "http://openphc.org/test|extra", "1.0"))
                .thenReturn(Optional.of(actionDef));

        ActionDefinition result = service.resolveByCanonical("http://openphc.org/test|extra|1.0");

        assertNotNull(result);
        verify(actionDefinitionRepository).findByCanonicalUrlAndVersion(
                "http://openphc.org/test|extra", "1.0");
    }

    private ActionDefinition buildActionDef(UUID id) {
        return ActionDefinition.builder()
                .id(id)
                .canonicalUrl("http://openphc.org/ActivityDefinition/test")
                .version("1.0")
                .name("test-action")
                .title("Test Action")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionDefinitionKind.CommunicationRequest)
                .definition(objectMapper.createObjectNode())
                .build();
    }
}
