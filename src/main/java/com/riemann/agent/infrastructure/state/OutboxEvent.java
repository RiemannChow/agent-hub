package com.riemann.agent.infrastructure.state;

import java.time.Instant;
import java.util.UUID;

/** A durable side-effect intent written before the actual side effect is executed. */
public record OutboxEvent(
        String id, String aggregateId, String type, String payload, Instant createdAt) {

    public OutboxEvent {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        payload = payload == null ? "" : payload;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static OutboxEvent of(String aggregateId, String type, String payload) {
        return new OutboxEvent(null, aggregateId, type, payload, Instant.now());
    }
}
