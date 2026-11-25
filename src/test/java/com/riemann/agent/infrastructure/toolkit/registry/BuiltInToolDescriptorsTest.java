package com.riemann.agent.infrastructure.toolkit.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuiltInToolDescriptorsTest {

    @Test
    void seedsAllShippedToolsWithVersion() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        BuiltInToolDescriptors.registerAll(registry);

        assertEquals(14, registry.list().size());
        assertTrue(registry.resolve("seek_sql_query").isPresent());
        assertTrue(registry.resolve("ob_cypher").isPresent());
        assertEquals(
                BuiltInToolDescriptors.VERSION,
                registry.resolve("seek_sql_query").orElseThrow().version());
    }
}
