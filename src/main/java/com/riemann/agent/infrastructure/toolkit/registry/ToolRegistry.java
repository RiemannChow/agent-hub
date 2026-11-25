package com.riemann.agent.infrastructure.toolkit.registry;

import java.util.List;
import java.util.Optional;

/** Registry of tool descriptors, keyed by name and version. */
public interface ToolRegistry {

    void register(ToolDescriptor descriptor);

    Optional<ToolDescriptor> resolve(String name);

    Optional<ToolDescriptor> resolve(String name, String version);

    List<ToolDescriptor> list();
}
