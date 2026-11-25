package com.riemann.agent.infrastructure.configuration;

/** Targeting attributes used by feature gate rules. */
public record FeatureContext(
        String userId, String conversationId, String runId, String environment) {

    public static FeatureContext of(String userId, String conversationId, String runId) {
        return new FeatureContext(userId, conversationId, runId, null);
    }
}
