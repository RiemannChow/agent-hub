package com.riemann.agent.infrastructure.state;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/** Shared helpers for the Redis state stores. */
public abstract class RedisSupport {

    protected final ReactiveStringRedisTemplate redis;
    protected final RedisJsonCodec codec;
    protected final StateBackendProperties props;

    protected RedisSupport(
            ReactiveStringRedisTemplate redis, RedisJsonCodec codec, StateBackendProperties props) {
        this.redis = redis;
        this.codec = codec;
        this.props = props;
    }

    /** Builds a namespaced key; user-controlled parts are escaped so they cannot forge keys. */
    protected String key(String... parts) {
        StringBuilder sb = new StringBuilder(props.getKeyPrefix());
        for (String part : parts) {
            sb.append(':')
                    .append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    /** Redis calls sit behind previously synchronous APIs; keep the wait short and explicit. */
    protected <T> T await(Mono<T> command) {
        T value = awaitOrNull(command);
        if (value == null) {
            throw new IllegalStateException("Redis state command returned no result");
        }
        return value;
    }

    /** For reads where an empty Redis key is a legitimate result. */
    protected <T> T awaitOrNull(Mono<T> command) {
        return command.block(props.getCommandTimeout());
    }

    protected static Duration untilEndOfDay() {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate tomorrow = today.plusDays(1);
        long seconds =
                java.time.Duration.between(java.time.LocalDateTime.now(), tomorrow.atStartOfDay())
                        .getSeconds();
        return Duration.ofSeconds(Math.max(60, seconds));
    }
}
