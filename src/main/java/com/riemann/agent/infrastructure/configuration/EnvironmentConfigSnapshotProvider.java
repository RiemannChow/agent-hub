package com.riemann.agent.infrastructure.configuration;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/** Builds a snapshot of all {@code agent.*} properties from the Spring Environment. */
@Component
public class EnvironmentConfigSnapshotProvider implements ConfigSnapshotProvider {

    private static final String PREFIX = "agent.";

    private final ConfigurableEnvironment environment;
    private final String version;

    public EnvironmentConfigSnapshotProvider(
            Environment environment, @Value("${agent.config.version:local}") String version) {
        this.environment = (ConfigurableEnvironment) environment;
        this.version = version;
    }

    @Override
    public ConfigSnapshot current() {
        Map<String, String> values = new TreeMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                if (!key.startsWith(PREFIX)) {
                    continue;
                }
                Object value = enumerable.getProperty(key);
                if (value != null) {
                    values.put(key, String.valueOf(value));
                }
            }
        }
        return ConfigSnapshot.of(version, values);
    }
}
