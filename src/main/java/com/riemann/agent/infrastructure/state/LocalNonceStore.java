package com.riemann.agent.infrastructure.state;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Original in-process signature replay guard. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "local", matchIfMissing = true)
public class LocalNonceStore implements NonceStore {

    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    @Override
    public boolean register(String signature, long maxAgeSeconds) {
        long now = Instant.now().getEpochSecond();
        seen.entrySet().removeIf(e -> now - e.getValue() > maxAgeSeconds);
        return seen.putIfAbsent(signature, now) == null;
    }
}
