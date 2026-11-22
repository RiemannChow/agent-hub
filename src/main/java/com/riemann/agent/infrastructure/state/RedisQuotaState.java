package com.riemann.agent.infrastructure.state;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Redis counters shared by every replica: budget checks and slot acquisition are atomic. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisQuotaState extends RedisSupport implements QuotaState {

    private static final RedisScript<Long> ACQUIRE =
            new DefaultRedisScript<>(
                    """
                    local daily = tonumber(ARGV[1])
                    local maxConcurrent = tonumber(ARGV[2])
                    local ttl = tonumber(ARGV[3])
                    local used = tonumber(redis.call('GET', KEYS[2]) or '0')
                    if daily > 0 and used >= daily then return 1 end
                    local current = tonumber(redis.call('GET', KEYS[1]) or '0') + 1
                    if maxConcurrent > 0 and current > maxConcurrent then return 2 end
                    redis.call('SET', KEYS[1], current, 'EX', ttl)
                    return 0
                    """,
                    Long.class);

    private static final RedisScript<Long> RELEASE =
            new DefaultRedisScript<>(
                    """
                    local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                    if current <= 1 then redis.call('DEL', KEYS[1])
                    else redis.call('DECR', KEYS[1]) end
                    return 0
                    """,
                    Long.class);

    private static final long CONCURRENCY_TTL_SECONDS = 3600;

    public RedisQuotaState(
            ReactiveStringRedisTemplate redis, RedisJsonCodec codec, StateBackendProperties props) {
        super(redis, codec, props);
    }

    @Override
    public int acquire(String userId, long dailyTokenLimit, int maxConcurrentTurns) {
        String user = safe(userId);
        return await(
                        redis.execute(
                                        ACQUIRE,
                                        List.of(concurrencyKey(user), tokensKey(user)),
                                        List.of(
                                                String.valueOf(dailyTokenLimit),
                                                String.valueOf(maxConcurrentTurns),
                                                String.valueOf(CONCURRENCY_TTL_SECONDS)))
                                .next())
                .intValue();
    }

    @Override
    public void release(String userId) {
        await(redis.execute(RELEASE, List.of(concurrencyKey(safe(userId)))).next());
    }

    @Override
    public long recordTokens(String userId, long tokens, long dailyTokenLimit) {
        if (tokens <= 0) {
            return tokensUsedToday(userId);
        }
        String key = tokensKey(safe(userId));
        return await(
                redis.opsForValue()
                        .increment(key, tokens)
                        .flatMap(total -> redis.expire(key, untilEndOfDay()).thenReturn(total)));
    }

    @Override
    public long tokensUsedToday(String userId) {
        return Optional.ofNullable(awaitOrNull(redis.opsForValue().get(tokensKey(safe(userId)))))
                .map(Long::parseLong)
                .orElse(0L);
    }

    @Override
    public int concurrentTurns(String userId) {
        return Optional.ofNullable(
                        awaitOrNull(redis.opsForValue().get(concurrencyKey(safe(userId)))))
                .map(Integer::parseInt)
                .orElse(0);
    }

    private String concurrencyKey(String user) {
        return key("state", "quota", user, "concurrent");
    }

    private String tokensKey(String user) {
        return key("state", "quota", user, "tokens", LocalDate.now().toString());
    }

    private static String safe(String userId) {
        return (userId == null || userId.isBlank()) ? "anonymous" : userId;
    }
}
