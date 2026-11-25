package com.riemann.agent.infrastructure.loop;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * Adapter that keeps AgentScope ReActAgent as the default loop implementation.
 *
 * <p>It adds the new {@code agent.run.id} runtime key without changing how AgentScope drives the
 * loop, so the first slice is behaviour neutral. Future engines can be swapped in through the
 * {@link LoopEngine} interface.
 */
public class AgentScopeLoopEngine implements LoopEngine {

    public static final String RUN_ID_KEY = "agent.run.id";

    private final ReActAgent agent;

    public AgentScopeLoopEngine(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    public Flux<AgentEvent> execute(LoopRequest request) {
        return Flux.defer(
                () -> {
                    request.runtimeContext().put(RUN_ID_KEY, request.runId());
                    return agent.streamEvents(request.messages(), request.runtimeContext());
                });
    }
}
