package com.riemann.agent.infrastructure.state;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Approval metadata in Redis; the executable action remains on its originating pod. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisApprovalStore extends RedisSupport implements ApprovalStore {

    private static final RedisScript<Long> CLAIM =
            new DefaultRedisScript<>(
                    """
                    local raw = redis.call('GET', KEYS[1])
                    if not raw then return 0 end
                    local data = cjson.decode(raw)
                    if data.userId ~= ARGV[1] then return 2 end
                    redis.call('DEL', KEYS[1])
                    return 1
                    """,
                    Long.class);

    public RedisApprovalStore(
            ReactiveStringRedisTemplate redis, RedisJsonCodec codec, StateBackendProperties props) {
        super(redis, codec, props);
    }

    @Override
    public void save(ApprovalMetadata metadata) {
        Duration ttl =
                Duration.ofMillis(
                        Math.max(
                                1000,
                                metadata.expiresAt().toEpochMilli() - System.currentTimeMillis()));
        await(redis.opsForValue().set(approvalKey(metadata.id()), codec.write(metadata), ttl));
    }

    @Override
    public Optional<ApprovalMetadata> view(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String json = awaitOrNull(redis.opsForValue().get(approvalKey(id)));
        if (json == null) {
            return Optional.empty();
        }
        ApprovalMetadata metadata = codec.read(json, ApprovalMetadata.class);
        return Instant.now().isBefore(metadata.expiresAt())
                ? Optional.of(metadata)
                : Optional.empty();
    }

    @Override
    public Claim claim(String id, String userId) {
        ApprovalMetadata metadata = view(id).orElse(null);
        if (metadata == null) {
            return Claim.notFound();
        }
        Long result =
                await(
                        redis.execute(
                                        CLAIM,
                                        List.of(approvalKey(id)),
                                        List.of(userId == null ? "" : userId))
                                .next());
        if (result == 0L) {
            return Claim.notFound();
        }
        if (result == 2L) {
            return Claim.forbidden();
        }
        return new Claim(ClaimStatus.CLAIMED, metadata);
    }

    @Override
    public void remove(String id) {
        await(redis.opsForValue().delete(approvalKey(id)));
    }

    private String approvalKey(String id) {
        return key("state", "approval", id);
    }
}
