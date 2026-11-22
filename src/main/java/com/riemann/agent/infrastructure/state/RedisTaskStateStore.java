package com.riemann.agent.infrastructure.state;

import com.riemann.agent.infrastructure.a2a.A2aTaskStoreProperties;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Redis-backed A2A task store: any replica can serve tasks/get, tasks/list and tasks/cancel. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisTaskStateStore extends RedisSupport implements TaskStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskStateStore.class);

    private final A2aTaskStoreProperties props;

    public RedisTaskStateStore(
            ReactiveStringRedisTemplate redis,
            RedisJsonCodec codec,
            StateBackendProperties props,
            A2aTaskStoreProperties taskProps) {
        super(redis, codec, props);
        this.props = taskProps;
    }

    private record TaskRecord(
            Task task, String conversationId, String owner, long createdAt, long expiresAt) {

        boolean expired() {
            return Instant.now().toEpochMilli() > expiresAt;
        }

        boolean ownedBy(String caller) {
            return owner != null && owner.equals(caller);
        }
    }

    @Override
    public void put(Task task, String conversationId, String owner) {
        if (task == null || task.id() == null || owner == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        long expires = now + props.getTtl().toMillis();
        TaskRecord record = new TaskRecord(task, conversationId, owner, now, expires);
        await(
                redis.opsForValue()
                        .set(taskKey(task.id()), codec.write(record), props.getTtl())
                        .then(index(task, owner, now))
                        .then(evictOverflow()));
    }

    @Override
    public Optional<Task> get(String id, String owner) {
        if (id == null) {
            return Optional.empty();
        }
        TaskRecord record = load(id);
        if (record == null || record.expired()) {
            if (record != null) {
                unindex(id, record.owner());
            }
            return Optional.empty();
        }
        return record.ownedBy(owner) ? Optional.of(record.task()) : Optional.empty();
    }

    @Override
    public Optional<String> conversationIdOf(String id, String owner) {
        TaskRecord record = load(id);
        if (record == null || record.expired() || !record.ownedBy(owner)) {
            return Optional.empty();
        }
        return Optional.ofNullable(record.conversationId());
    }

    @Override
    public Optional<Task> computeIfPresent(String id, String owner, Function<Task, Task> update) {
        TaskRecord record = load(id);
        if (record == null || record.expired() || !record.ownedBy(owner)) {
            return Optional.empty();
        }
        Task next = update.apply(record.task());
        if (next == null) {
            return Optional.of(record.task());
        }
        Duration remaining =
                Duration.ofMillis(Math.max(1000, record.expiresAt() - System.currentTimeMillis()));
        TaskRecord updated =
                new TaskRecord(
                        next,
                        record.conversationId(),
                        record.owner(),
                        record.createdAt(),
                        record.expiresAt());
        await(redis.opsForValue().set(taskKey(id), codec.write(updated), remaining));
        return Optional.of(next);
    }

    @Override
    public List<Task> list(String owner, String contextId, TaskState state, int limit, int offset) {
        List<String> ids =
                await(
                        redis.opsForZSet()
                                .reverseRange(
                                        ownerIndex(owner),
                                        Range.closed(
                                                (long) Math.max(0, offset),
                                                (long)
                                                        (Math.max(0, offset)
                                                                + Math.max(1, limit)
                                                                - 1)))
                                .collectList());
        if (ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(this::load)
                .filter(r -> r != null && !r.expired() && r.ownedBy(owner))
                .filter(r -> contextId == null || contextId.equals(r.task().contextId()))
                .filter(
                        r ->
                                state == null
                                        || (r.task().status() != null
                                                && r.task().status().state() == state))
                .sorted(Comparator.comparingLong(TaskRecord::createdAt).reversed())
                .limit(Math.max(1, limit))
                .map(TaskRecord::task)
                .toList();
    }

    @Override
    public int count(String owner, String contextId, TaskState state) {
        return list(owner, contextId, state, Integer.MAX_VALUE / 4, 0).size();
    }

    @Override
    public int size() {
        Long count = awaitOrNull(redis.opsForZSet().size(allIndex()));
        return count == null ? 0 : count.intValue();
    }

    @Scheduled(fixedDelay = 60_000)
    @Override
    public void sweep() {
        List<String> ids =
                awaitOrNull(redis.opsForZSet().range(allIndex(), Range.unbounded()).collectList());
        if (ids == null || ids.isEmpty()) {
            return;
        }
        int removed = 0;
        for (String id : ids) {
            TaskRecord record = load(id);
            if (record == null || record.expired()) {
                unindex(id, record == null ? null : record.owner());
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[A2A] Swept {} stale Redis task records", removed);
        }
    }

    private TaskRecord load(String id) {
        String json = awaitOrNull(redis.opsForValue().get(taskKey(id)));
        return json == null ? null : codec.read(json, TaskRecord.class);
    }

    private reactor.core.publisher.Mono<Boolean> index(Task task, String owner, long createdAt) {
        return redis.opsForZSet()
                .add(ownerIndex(owner), task.id(), createdAt)
                .then(redis.opsForZSet().add(allIndex(), task.id(), createdAt));
    }

    private reactor.core.publisher.Mono<Boolean> evictOverflow() {
        int max = props.getMaxEntries();
        if (max <= 0) {
            return reactor.core.publisher.Mono.just(true);
        }
        return redis.opsForZSet()
                .size(allIndex())
                .flatMap(
                        size -> {
                            int excess = size.intValue() - max;
                            if (excess <= 0) {
                                return reactor.core.publisher.Mono.just(true);
                            }
                            return redis.opsForZSet()
                                    .range(allIndex(), Range.closed(0L, (long) excess - 1))
                                    .collectList()
                                    .flatMap(
                                            ids -> {
                                                List<reactor.core.publisher.Mono<?>> removals =
                                                        new ArrayList<>();
                                                ids.forEach(
                                                        id -> {
                                                            TaskRecord r = load(id);
                                                            removals.add(
                                                                    redis.opsForValue()
                                                                            .delete(taskKey(id)));
                                                            removals.add(
                                                                    unindexMono(
                                                                            id,
                                                                            r == null
                                                                                    ? null
                                                                                    : r.owner()));
                                                        });
                                                return reactor.core.publisher.Flux.concat(removals)
                                                        .then(Mono.just(true));
                                            });
                        });
    }

    private void unindex(String id, String owner) {
        await(unindexMono(id, owner));
    }

    private reactor.core.publisher.Mono<?> unindexMono(String id, String owner) {
        reactor.core.publisher.Mono<?> removeAll = redis.opsForZSet().remove(allIndex(), id);
        if (owner == null || owner.isBlank()) {
            return removeAll;
        }
        return removeAll.then(redis.opsForZSet().remove(ownerIndex(owner), id));
    }

    private String taskKey(String id) {
        return key("a2a", "task", id);
    }

    private String ownerIndex(String owner) {
        return key("a2a", "tasks", "owner", owner);
    }

    private String allIndex() {
        return key("a2a", "tasks", "all");
    }
}
