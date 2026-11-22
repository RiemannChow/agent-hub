package com.riemann.agent.infrastructure.state;

import com.riemann.agent.infrastructure.execution.ActiveTurnRegistry.ActiveTurn;
import java.util.Optional;

/** Storage strategy for the conversation-to-running-turn mapping. */
public interface ActiveTurnStore {

    void register(ActiveTurn turn);

    void unregister(ActiveTurn turn);

    Optional<ActiveTurn> find(String conversationId);

    int activeCount();
}
