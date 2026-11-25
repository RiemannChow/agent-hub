package com.riemann.agent.infrastructure.toolkit.registry;

import java.time.Instant;

/** One tool invocation record for audit and replay. */
public record ToolInvocationAudit(
        String runId,
        String stepId,
        String userId,
        String conversationId,
        String toolName,
        String toolVersion,
        String decision,
        boolean success,
        long latencyMs,
        String errorCode,
        Instant timestamp) {

    public ToolInvocationAudit {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        decision = decision == null ? "ALLOW" : decision;
        errorCode = errorCode == null ? "" : errorCode;
    }
}
