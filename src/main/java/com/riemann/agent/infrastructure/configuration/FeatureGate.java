package com.riemann.agent.infrastructure.configuration;

/** Stable, targeted feature flag evaluation. */
public interface FeatureGate {

    FeatureDecision evaluate(String key, FeatureContext context, String defaultValue);
}
