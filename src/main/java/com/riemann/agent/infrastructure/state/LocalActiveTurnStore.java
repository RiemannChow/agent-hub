package com.riemann.agent.infrastructure.state;

import com.riemann.agent.infrastructure.execution.ActiveTurnRegistry.ActiveTurn;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Original in-process active-turn registry. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "local", matchIfMissing = true)
public class LocalActiveTurnStore implements ActiveTurnStore {

    private final Map<String, ActiveTurn> byConversation = new ConcurrentHashMap<>();

    @Override
    public void register(ActiveTurn turn) {
        byConversation.put(turn.conversationId(), turn);
    }

    @Override
    public void unregister(ActiveTurn turn) {
        byConversation.remove(turn.conversationId(), turn);
    }

    @Override
    public Optional<ActiveTurn> find(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byConversation.get(conversationId));
    }

    @Override
    public int activeCount() {
        return byConversation.size();
    }
}
