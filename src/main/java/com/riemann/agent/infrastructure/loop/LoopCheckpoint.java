package com.riemann.agent.infrastructure.loop;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of one ReAct loop step.
 *
 * <p>This slice only defines the model and an in-memory store. Wiring checkpoint capture into the
 * orchestration service is intentionally left to the next slice so that this change is behaviour
 * neutral.
 */
public record LoopCheckpoint(
        String runId,
        int stepNo,
        Phase phase,
        String status,
        Map<String, String> refs,
        int tokensUsed,
        Instant updatedAt) {

    public enum Phase {
        INIT,
        CONTEXT,
        THINK,
        ACT,
        OBSERVE,
        RECOVER,
        VERIFY,
        FINALIZE
    }

    public LoopCheckpoint {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        refs = refs == null ? Map.of() : Map.copyOf(refs);
        status = status == null ? "RUNNING" : status;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
