package com.riemann.agent.infrastructure.execution;

import com.riemann.agent.infrastructure.state.ActiveTurnStore;
import com.riemann.agent.infrastructure.state.LocalActiveTurnStore;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Maps a client-visible conversationId to the per-turn (userId, sessionId) pair that AgentScope
 * needs in order to interrupt that turn. With the Redis backend the mapping is shared, so a stop
 * request landing on another replica can still locate the running turn.
 */
@Component
public class ActiveTurnRegistry {

    public record ActiveTurn(
            String conversationId, String userId, String sessionId, Instant startedAt) {}

    private final ActiveTurnStore store;

    public ActiveTurnRegistry() {
        this(new LocalActiveTurnStore());
    }

    @Autowired
    public ActiveTurnRegistry(ActiveTurnStore store) {
        this.store = store;
    }

    public void register(ActiveTurn turn) {
        store.register(turn);
    }

    public void unregister(ActiveTurn turn) {
        store.unregister(turn);
    }

    public Optional<ActiveTurn> find(String conversationId) {
        return store.find(conversationId);
    }

    public int activeCount() {
        return store.activeCount();
    }
}
