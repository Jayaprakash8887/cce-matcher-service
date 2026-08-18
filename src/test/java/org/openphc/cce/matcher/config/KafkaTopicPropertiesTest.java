package org.openphc.cce.matcher.config;

import org.openphc.cce.common.kafka.KafkaTopicProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaTopicPropertiesTest {

    @Test
    void shouldBindTopicProperties() {
        KafkaTopicProperties props = new KafkaTopicProperties();
        props.setInboundEvents("cce.events.inbound");
        props.setIntelligenceTriggers("cce.intelligence.triggers");
        props.setDefaultPartitions(25);

        assertEquals("cce.events.inbound", props.getInboundEvents());
        assertEquals("cce.intelligence.triggers", props.getIntelligenceTriggers());
        assertEquals(25, props.getDefaultPartitions());
    }

    @Test
    void shouldDefaultToNull() {
        KafkaTopicProperties props = new KafkaTopicProperties();

        assertNull(props.getInboundEvents());
        assertNull(props.getIntelligenceTriggers());
        assertEquals(25, props.getDefaultPartitions());
    }
}
