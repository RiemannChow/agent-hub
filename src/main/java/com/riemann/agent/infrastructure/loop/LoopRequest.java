package com.riemann.agent.infrastructure.loop;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;

/** Input of one loop execution. */
public record LoopRequest(
        String runId,
        String conversationId,
        String userId,
        List<Msg> messages,
        RuntimeContext runtimeContext) {

    public LoopRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
