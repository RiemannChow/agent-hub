package com.riemann.agent.infrastructure.loop;

import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * SPI for agent execution loops.
 *
 * <p>The existing {@code ReActAgent} is wrapped by {@link AgentScopeLoopEngine}; future graph or
 * workflow engines can implement the same contract without touching orchestration services.
 */
public interface LoopEngine {

    Flux<AgentEvent> execute(LoopRequest request);
}
