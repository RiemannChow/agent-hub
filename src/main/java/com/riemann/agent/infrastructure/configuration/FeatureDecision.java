package com.riemann.agent.infrastructure.configuration;

/** Result of a feature gate evaluation, including the matched rule for audit. */
public record FeatureDecision(String value, String matchedRule) {

    public boolean isEnabled() {
        return Boolean.parseBoolean(value);
    }
}
