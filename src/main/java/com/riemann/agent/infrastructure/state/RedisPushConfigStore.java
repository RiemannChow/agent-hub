package com.riemann.agent.infrastructure.state;

import com.riemann.agent.presentation.a2a.protocol.PushNotificationConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

/** Push configs keyed by task id, so any replica can deliver notifications. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisPushConfigStore extends RedisSupport implements PushConfigStore {

    private static final Duration TTL = Duration.ofHours(1);

    private final ReactiveHashOperations<String, String, String> hash;

    public RedisPushConfigStore(
            ReactiveStringRedisTemplate redis, RedisJsonCodec codec, StateBackendProperties props) {
        super(redis, codec, props);
        this.hash = redis.opsForHash();
    }

    @Override
    public void set(String taskId, PushNotificationConfig config, int maxConfigs) {
        String key = taskKey(taskId);
        List<Map.Entry<String, String>> existing = await(hash.entries(key).collectList());
        if (!existing.stream().anyMatch(e -> e.getKey().equals(config.id()))
                && existing.size() >= maxConfigs) {
            throw new IllegalArgumentException("Too many push notification configs for this task");
        }
        await(hash.put(key, config.id(), codec.write(config)).then(redis.expire(key, TTL)));
    }

    @Override
    public Optional<PushNotificationConfig> get(String taskId, String configId) {
        String json =
                awaitOrNull(hash.get(taskKey(taskId), configId == null ? "default" : configId));
        return json == null
                ? Optional.empty()
                : Optional.of(codec.read(json, PushNotificationConfig.class));
    }

    @Override
    public List<PushNotificationConfig> list(String taskId) {
        return await(
                        hash.values(taskKey(taskId))
                                .map(json -> codec.read(json, PushNotificationConfig.class))
                                .collectList())
                .stream()
                .toList();
    }

    @Override
    public boolean delete(String taskId, String configId) {
        return await(
                hash.remove(taskKey(taskId), configId == null ? "default" : configId)
                        .map(count -> count > 0)
                        .defaultIfEmpty(false));
    }

    private String taskKey(String taskId) {
        return key("a2a", "push", taskId);
    }
}
