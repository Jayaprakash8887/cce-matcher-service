package org.openphc.cce.matcher.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code @PrePersist} / {@code @PreUpdate} callbacks stamp columns the schema declares NOT NULL,
 * so a missed default surfaces as a constraint violation at insert time. They also preserve a
 * caller-supplied value, which matters for the times that come off the inbound event rather than off
 * the clock.
 */
class EntityLifecycleCallbacksTest {

    private static final OffsetDateTime EXPLICIT =
            OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void matcherEventLog_stampsReceivedAtThenPreservesIt() {
        MatcherEventLog entry = MatcherEventLog.builder().build();
        entry.onCreate();

        assertNotNull(entry.getReceivedAt());
        assertNotNull(entry.getUpdatedAt());

        MatcherEventLog replayed = MatcherEventLog.builder().receivedAt(EXPLICIT).build();
        replayed.onCreate();
        assertEquals(EXPLICIT, replayed.getReceivedAt(),
                "a replayed event keeps the time it was originally received");

        replayed.onUpdate();
        assertTrue(replayed.getUpdatedAt().isAfter(EXPLICIT));
    }

    @Test
    void facility_stampsCreatedAndUpdated() {
        Facility facility = Facility.builder().facilityId("PHC-001").build();
        facility.onCreate();

        assertNotNull(facility.getCreatedAt());
        assertNotNull(facility.getUpdatedAt());

        Facility seeded = Facility.builder().facilityId("PHC-002").createdAt(EXPLICIT).build();
        seeded.onCreate();
        assertEquals(EXPLICIT, seeded.getCreatedAt());

        seeded.onUpdate();
        assertTrue(seeded.getUpdatedAt().isAfter(EXPLICIT));
    }
}
