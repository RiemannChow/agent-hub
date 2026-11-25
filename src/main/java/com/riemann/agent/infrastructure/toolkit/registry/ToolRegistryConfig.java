package com.riemann.agent.infrastructure.toolkit.registry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the in-memory registry and audit store used by this first slice. */
@Configuration
public class ToolRegistryConfig {

    @Bean
    public ToolRegistry toolRegistry(ToolRegistryProperties properties) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        if (properties.isSeedBuiltIns()) {
            BuiltInToolDescriptors.registerAll(registry);
        }
        return registry;
    }

    @Bean
    public ToolAuditStore toolAuditStore(ToolRegistryProperties properties) {
        return new InMemoryToolAuditStore(properties.getAuditCapacityPerRun());
    }
}
