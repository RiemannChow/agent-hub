package com.riemann.agent.infrastructure.state;

import com.riemann.agent.infrastructure.a2a.A2aTaskStoreProperties;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Original in-process A2A task store, with TTL, capacity bound and owner isolation. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "local", matchIfMissing = true)
public class LocalTaskStateStore implements TaskStateStore {

    private static final Logger log = LoggerFactory.getLogger(LocalTaskStateStore.class);

    private final A2aTaskStoreProperties props;
    private final Map<String, Entry> tasks = new ConcurrentHashMap<>();

    public LocalTaskStateStore(A2aTaskStoreProperties props) {
        this.props = props;
    }

    private record Entry(
            Task task, String conversationId, String owner, Instant createdAt, Instant expiresAt) {

        boolean expired() {
            return Instant.now().isAfter(expiresAt);
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
        Instant now = Instant.now();
        tasks.put(task.id(), new Entry(task, conversationId, owner, now, now.plus(props.getTtl())));
        evictIfNeeded();
    }

    @Override
    public Optional<Task> get(String id, String owner) {
        Entry e = tasks.get(id);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expired()) {
            tasks.remove(id, e);
            return Optional.empty();
        }
        return e.ownedBy(owner) ? Optional.of(e.task()) : Optional.empty();
    }

    @Override
    public Optional<String> conversationIdOf(String id, String owner) {
        Entry e = tasks.get(id);
        if (e == null || e.expired() || !e.ownedBy(owner)) {
            return Optional.empty();
        }
        return Optional.ofNullable(e.conversationId());
    }

    @Override
    public Optional<Task> computeIfPresent(String id, String owner, Function<Task, Task> fn) {
        Entry current = tasks.get(id);
        if (current == null || current.expired() || !current.ownedBy(owner)) {
            return Optional.empty();
        }
        Entry updated =
                tasks.computeIfPresent(
                        id,
                        (k, old) -> {
                            if (!old.ownedBy(owner)) {
                                return old;
                            }
                            Task next = fn.apply(old.task());
                            return next == null
                                    ? old
                                    : new Entry(
                                            next,
                                            old.conversationId(),
                                            old.owner(),
                                            old.createdAt(),
                                            old.expiresAt());
                        });
        return updated == null ? Optional.empty() : Optional.of(updated.task());
    }

    @Override
    public List<Task> list(String owner, String contextId, TaskState state, int limit, int offset) {
        return tasks.values().stream()
                .filter(e -> !e.expired() && e.ownedBy(owner))
                .filter(e -> contextId == null || contextId.equals(e.task().contextId()))
                .filter(
                        e ->
                                state == null
                                        || (e.task().status() != null
                                                && e.task().status().state() == state))
                .sorted(Comparator.comparing(Entry::createdAt).reversed())
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(Entry::task)
                .toList();
    }

    @Override
    public int count(String owner, String contextId, TaskState state) {
        return (int)
                tasks.values().stream()
                        .filter(e -> !e.expired() && e.ownedBy(owner))
                        .filter(e -> contextId == null || contextId.equals(e.task().contextId()))
                        .filter(
                                e ->
                                        state == null
                                                || (e.task().status() != null
                                                        && e.task().status().state() == state))
                        .count();
    }

    @Override
    public int size() {
        return tasks.size();
    }

    @Scheduled(fixedDelay = 60_000)
    @Override
    public void sweep() {
        int before = tasks.size();
        tasks.entrySet().removeIf(e -> e.getValue().expired());
        int removed = before - tasks.size();
        if (removed > 0) {
            log.debug("[A2A] 清理过期任务 {} 个，剩余 {}", removed, tasks.size());
        }
    }

    private void evictIfNeeded() {
        int max = props.getMaxEntries();
        if (max <= 0 || tasks.size() <= max) {
            return;
        }
        List<Map.Entry<String, Entry>> ordered =
                tasks.entrySet().stream()
                        .sorted(Comparator.comparing(e -> e.getValue().createdAt()))
                        .toList();
        int toRemove = tasks.size() - max;
        for (int i = 0; i < toRemove && i < ordered.size(); i++) {
            tasks.remove(ordered.get(i).getKey(), ordered.get(i).getValue());
        }
        log.debug("[A2A] 任务数超过上限 {}，已淘汰最旧的 {} 条", max, toRemove);
    }
}
