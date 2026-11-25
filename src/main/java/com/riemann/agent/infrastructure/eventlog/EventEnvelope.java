package com.riemann.agent.infrastructure.eventlog;

import java.time.Instant;
import java.util.UUID;

/** Immutable event record used for replay and audit. */
public record EventEnvelope(
        String eventId,
        String runId,
        long sequence,
        String type,
        String schemaVersion,
        String traceId,
        String configVersion,
        String payload,
        Instant timestamp) {

    public static final String SCHEMA_VERSION = "1.0.0";

    public EventEnvelope {
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId;
        schemaVersion =
                schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        payload = payload == null ? "" : payload;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static EventEnvelope of(
            String runId, String type, String traceId, String configVersion, String payload) {
        return new EventEnvelope(
                null,
                runId,
                0L,
                type,
                SCHEMA_VERSION,
                traceId,
                configVersion,
                payload,
                Instant.now());
    }
}
