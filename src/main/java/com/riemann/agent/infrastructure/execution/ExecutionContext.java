package com.riemann.agent.infrastructure.execution;

/** Correlation identifiers for one request / run / step. */
public record ExecutionContext(
        String traceId,
        String runId,
        String conversationId,
        String userId,
        String stepId,
        String configVersion) {

    /** Key used to pass the context through AgentScope RuntimeContext. */
    public static final String RUNTIME_KEY = "agent.execution.context";

    public ExecutionContext {
        traceId = traceId == null || traceId.isBlank() ? newId() : traceId;
        runId = runId == null || runId.isBlank() ? newId() : runId;
    }

    public static ExecutionContext forRequest(
            String traceId, String conversationId, String userId) {
        return new ExecutionContext(traceId, newId(), conversationId, userId, null, null);
    }

    public static String newId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
