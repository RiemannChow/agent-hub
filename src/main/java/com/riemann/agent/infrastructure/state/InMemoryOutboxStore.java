package com.riemann.agent.infrastructure.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded in-memory outbox; a DB-backed implementation can replace it without caller changes. */
public class InMemoryOutboxStore implements OutboxStore {

    private final int capacity;
    private final Map<String, OutboxEvent> pending = new LinkedHashMap<>();

    public InMemoryOutboxStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void enqueue(OutboxEvent event) {
        pending.put(event.id(), event);
        evictIfNeeded();
    }

    @Override
    public synchronized List<OutboxEvent> pending() {
        return List.copyOf(pending.values());
    }

    @Override
    public synchronized void markProcessed(String eventId) {
        pending.remove(eventId);
    }

    public synchronized int size() {
        return pending.size();
    }

    private void evictIfNeeded() {
        while (pending.size() > capacity) {
            String oldest = pending.keySet().iterator().next();
            pending.remove(oldest);
        }
    }

    /** Test helper returning a mutable snapshot, used to assert insertion order. */
    public synchronized List<OutboxEvent> snapshot() {
        return new ArrayList<>(pending.values());
    }
}
