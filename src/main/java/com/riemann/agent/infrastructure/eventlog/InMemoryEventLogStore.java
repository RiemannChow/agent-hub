package com.riemann.agent.infrastructure.eventlog;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded in-memory event log; a DB/Redis implementation can replace it behind the same SPI. */
public class InMemoryEventLogStore implements EventLogStore {

    private final int capacityPerRun;
    private final Map<String, Deque<EventEnvelope>> events = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public InMemoryEventLogStore(int capacityPerRun) {
        if (capacityPerRun <= 0) {
            throw new IllegalArgumentException("capacityPerRun must be greater than 0");
        }
        this.capacityPerRun = capacityPerRun;
    }

    @Override
    public EventEnvelope append(EventEnvelope event) {
        long sequence =
                sequences.computeIfAbsent(event.runId(), key -> new AtomicLong()).incrementAndGet();
        EventEnvelope stored =
                new EventEnvelope(
                        event.eventId(),
                        event.runId(),
                        sequence,
                        event.type(),
                        event.schemaVersion(),
                        event.traceId(),
                        event.configVersion(),
                        event.payload(),
                        event.timestamp());
        Deque<EventEnvelope> history =
                events.computeIfAbsent(event.runId(), key -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(stored);
            while (history.size() > capacityPerRun) {
                history.removeFirst();
            }
        }
        return stored;
    }

    @Override
    public List<EventEnvelope> query(String runId) {
        return query(runId, 0L);
    }

    @Override
    public List<EventEnvelope> query(String runId, long fromSequence) {
        Deque<EventEnvelope> history = events.get(runId);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return history.stream().filter(event -> event.sequence() >= fromSequence).toList();
        }
    }
}
