package com.riemann.agent.infrastructure.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyGuardTest {

    @Test
    void claimsKeyOnlyOnceWithinTtl() {
        Instant start = Instant.parse("2026-11-25T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard(Duration.ofMinutes(5), clock);

        assertTrue(guard.tryAcquire("conv-1:msg-1"));
        assertFalse(guard.tryAcquire("conv-1:msg-1"));
        assertTrue(guard.tryAcquire("conv-1:msg-2"));

        clock.advance(Duration.ofMinutes(6));
        assertTrue(guard.tryAcquire("conv-1:msg-1"));
    }

    @Test
    void blankKeysAreNotTracked() {
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard(Duration.ofMinutes(5));

        assertTrue(guard.tryAcquire(" "));
        assertTrue(guard.tryAcquire(" "));
        assertEquals(0, guard.size());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
