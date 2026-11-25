package com.riemann.agent.infrastructure.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AgentLoopPropertiesTest {

    @Test
    void defaultsPreserveLegacyBehaviour() {
        AgentLoopProperties properties = new AgentLoopProperties();

        assertEquals(30, properties.getMaxIters());
        assertEquals(16, properties.getCheckpointHistorySize());
    }

    @Test
    void rejectsNonPositiveMaxIters() {
        AgentLoopProperties properties = new AgentLoopProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setMaxIters(0));
        assertEquals(30, properties.getMaxIters());
    }

    @Test
    void acceptsConfiguredValues() {
        AgentLoopProperties properties = new AgentLoopProperties();

        properties.setMaxIters(50);
        properties.setCheckpointHistorySize(4);

        assertEquals(50, properties.getMaxIters());
        assertEquals(4, properties.getCheckpointHistorySize());
    }
}
