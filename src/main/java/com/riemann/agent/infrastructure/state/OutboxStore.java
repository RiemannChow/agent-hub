package com.riemann.agent.infrastructure.state;

import java.util.List;

/** Transactional-outbox abstraction for state side effects. */
public interface OutboxStore {

    void enqueue(OutboxEvent event);

    List<OutboxEvent> pending();

    void markProcessed(String eventId);
}
