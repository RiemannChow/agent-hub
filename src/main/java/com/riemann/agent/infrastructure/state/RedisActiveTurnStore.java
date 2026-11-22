package com.riemann.agent.infrastructure.state;

import com.riemann.agent.infrastructure.execution.ActiveTurnRegistry.ActiveTurn;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

/** Active turns visible to every replica, so stop/cancel can find the pod that owns a turn. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisActiveTurnStore extends RedisSupport implements ActiveTurnStore {

    private static final Duration TTL = Duration.ofHours(1);

    public RedisActiveTurnStore(
            ReactiveStringRedisTemplate redis, RedisJsonCodec codec, StateBackendProperties props) {
        super(redis, codec, props);
    }

    private record StoredTurn(
            String conversationId, String userId, String sessionId, long startedAt) {}

    @Override
    public void register(ActiveTurn turn) {
        StoredTurn stored =
                new StoredTurn(
                        turn.conversationId(),
                        turn.userId(),
                        turn.sessionId(),
                        turn.startedAt().toEpochMilli());
        await(redis.opsForValue().set(turnKey(turn.conversationId()), codec.write(stored), TTL));
    }

    @Override
    public void unregister(ActiveTurn turn) {
        String json = awaitOrNull(redis.opsForValue().get(turnKey(turn.conversationId())));
        if (json == null) {
            return;
        }
        StoredTurn stored = codec.read(json, StoredTurn.class);
        // Compare the session id so an older turn cannot delete a newer registration.
        if (stored.sessionId().equals(turn.sessionId())) {
            await(redis.opsForValue().delete(turnKey(turn.conversationId())));
        }
    }

    @Override
    public Optional<ActiveTurn> find(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        String json = awaitOrNull(redis.opsForValue().get(turnKey(conversationId)));
        if (json == null) {
            return Optional.empty();
        }
        StoredTurn stored = codec.read(json, StoredTurn.class);
        return Optional.of(
                new ActiveTurn(
                        stored.conversationId(),
                        stored.userId(),
                        stored.sessionId(),
                        Instant.ofEpochMilli(stored.startedAt())));
    }

    @Override
    public int activeCount() {
        ScanOptions options =
                ScanOptions.scanOptions().match(key("state", "turn", "*")).count(100).build();
        Long count = await(redis.scan(options).count());
        return count.intValue();
    }

    private String turnKey(String conversationId) {
        return key("state", "turn", conversationId);
    }
}
