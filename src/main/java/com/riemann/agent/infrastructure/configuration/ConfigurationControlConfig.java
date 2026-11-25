package com.riemann.agent.infrastructure.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans for immutable config snapshots and targeted feature rollout. */
@Configuration
public class ConfigurationControlConfig {

    @Bean
    public FeatureGate featureGate(FeatureGateProperties properties) {
        return new StableHashFeatureGate(properties);
    }
}
