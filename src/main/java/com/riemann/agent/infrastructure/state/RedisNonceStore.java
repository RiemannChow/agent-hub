package com.riemann.agent.infrastructure.state;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis SET NX makes signature replay protection effective across replicas. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisNonceStore extends RedisSupport implements NonceStore {

    public RedisNonceStore(
            ReactiveStringRedisTemplate redis, RedisJsonCodec codec, StateBackendProperties props) {
        super(redis, codec, props);
    }

    @Override
    public boolean register(String signature, long maxAgeSeconds) {
        String key = key("a2a", "nonce", signature);
        return Boolean.TRUE.equals(
                await(
                        redis.opsForValue()
                                .setIfAbsent(
                                        key,
                                        Long.toString(Instant.now().getEpochSecond()),
                                        Duration.ofSeconds(Math.max(1, maxAgeSeconds)))
                                .defaultIfEmpty(false)));
    }
}
