package com.riemann.agent.infrastructure.state;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Bounded in-memory idempotency guard with TTL. */
public class InMemoryIdempotencyGuard implements IdempotencyGuard {

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Instant> claimed = new HashMap<>();

    public InMemoryIdempotencyGuard(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    public InMemoryIdempotencyGuard(Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public synchronized boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        Instant now = clock.instant();
        claimed.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        if (claimed.containsKey(key)) {
            return false;
        }
        claimed.put(key, now.plus(ttl));
        return true;
    }

    public synchronized int size() {
        return claimed.size();
    }
}
