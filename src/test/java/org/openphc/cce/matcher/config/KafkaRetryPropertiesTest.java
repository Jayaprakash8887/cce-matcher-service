package org.openphc.cce.matcher.config;

import org.openphc.cce.common.config.KafkaRetryProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaRetryPropertiesTest {

    @Test
    void shouldHaveDefaults() {
        KafkaRetryProperties props = new KafkaRetryProperties();
        assertEquals(3, props.getMaxAttempts());
        assertEquals(1000, props.getBackoffIntervalMs());
    }

    @Test
    void shouldBindCustomValues() {
        KafkaRetryProperties props = new KafkaRetryProperties();
        props.setMaxAttempts(5);
        props.setBackoffIntervalMs(2000);

        assertEquals(5, props.getMaxAttempts());
        assertEquals(2000, props.getBackoffIntervalMs());
    }
}
