package com.riemann.agent.infrastructure.toolkit.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InMemoryToolRegistryTest {

    @Test
    void resolvesLatestActiveVersion() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(descriptor("demo", "1.0.0", ToolDescriptor.Status.ACTIVE));
        registry.register(descriptor("demo", "1.2.0", ToolDescriptor.Status.ACTIVE));
        registry.register(descriptor("demo", "2.0.0", ToolDescriptor.Status.DEPRECATED));

        assertEquals("1.2.0", registry.resolve("demo").orElseThrow().version());
        assertEquals("1.0.0", registry.resolve("demo", "1.0.0").orElseThrow().version());
        assertTrue(registry.resolve("missing").isEmpty());
    }

    @Test
    void rejectsDuplicateNameAndVersion() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(descriptor("demo", "1.0.0", ToolDescriptor.Status.ACTIVE));

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.register(descriptor("demo", "1.0.0", ToolDescriptor.Status.ACTIVE)));
        assertEquals(1, registry.list().size());
    }

    private static ToolDescriptor descriptor(
            String name, String version, ToolDescriptor.Status status) {
        return new ToolDescriptor(
                name,
                version,
                "1.0.0",
                ToolDescriptor.Risk.LOW,
                ToolDescriptor.SideEffect.READ,
                status,
                "test",
                "test tool");
    }
}
