package com.riemann.agent.infrastructure.state;

import com.riemann.agent.domain.AgentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/** Bridges a conversation's SSE sink to Redis pub/sub so another replica can replay it. */
public interface EventBusBridge {

    /**
     * @param publishLocal true for the pod that drives the turn; false for a replay-only sink
     * @return subscriptions that must be disposed when the sink is released
     */
    Handle attach(String conversationId, Sinks.Many<AgentEvent> sink, boolean publishLocal);

    interface Handle extends Disposable {}
}
