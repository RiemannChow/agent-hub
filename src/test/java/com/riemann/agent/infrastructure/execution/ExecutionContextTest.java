package com.riemann.agent.infrastructure.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExecutionContextTest {

    @Test
    void forRequestGeneratesRunId() {
        ExecutionContext context = ExecutionContext.forRequest("trace-1", "conv-1", "user-1");

        assertEquals("trace-1", context.traceId());
        assertEquals("conv-1", context.conversationId());
        assertEquals("user-1", context.userId());
        assertNotEquals(context.traceId(), context.runId());
        assertTrue(context.runId().matches("[0-9a-f]{32}"));
    }

    @Test
    void blankIdsAreReplaced() {
        ExecutionContext context = new ExecutionContext(" ", "", null, null, null, null);

        assertTrue(context.traceId().matches("[0-9a-f]{32}"));
        assertTrue(context.runId().matches("[0-9a-f]{32}"));
    }
}
