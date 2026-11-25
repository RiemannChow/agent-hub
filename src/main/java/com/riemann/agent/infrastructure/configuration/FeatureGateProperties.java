package com.riemann.agent.infrastructure.configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Feature rules configured under {@code agent.features.flags.*}. */
@Component
@ConfigurationProperties(prefix = "agent.features")
public class FeatureGateProperties {

    private final Map<String, FeatureRuleConfig> flags = new LinkedHashMap<>();

    public Map<String, FeatureRuleConfig> getFlags() {
        return flags;
    }
}
