package com.riemann.agent.infrastructure.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoopRequestTest {

    @Test
    void rejectsBlankRunId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LoopRequest(
                                " ",
                                "conv-1",
                                "user-1",
                                List.of(),
                                RuntimeContext.builder().build()));
    }

    @Test
    void normalisesNullMessages() {
        LoopRequest request =
                new LoopRequest(
                        "run-1", "conv-1", "user-1", null, RuntimeContext.builder().build());

        assertEquals(List.of(), request.messages());
        assertEquals("run-1", request.runId());
    }
}
