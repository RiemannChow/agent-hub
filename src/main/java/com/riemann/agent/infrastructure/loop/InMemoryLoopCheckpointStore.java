package com.riemann.agent.infrastructure.loop;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded in-memory checkpoint store.
 *
 * <p>Suitable for local development and single-instance deployments. A Redis/DB implementation can
 * replace this bean without changing callers because all access goes through {@link
 * LoopCheckpointStore}.
 */
public class InMemoryLoopCheckpointStore implements LoopCheckpointStore {

    private final int historySize;
    private final ConcurrentHashMap<String, Deque<LoopCheckpoint>> checkpoints =
            new ConcurrentHashMap<>();

    public InMemoryLoopCheckpointStore(int historySize) {
        if (historySize <= 0) {
            throw new IllegalArgumentException("historySize must be greater than 0");
        }
        this.historySize = historySize;
    }

    @Override
    public void save(LoopCheckpoint checkpoint) {
        Deque<LoopCheckpoint> history =
                checkpoints.computeIfAbsent(checkpoint.runId(), key -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(checkpoint);
            while (history.size() > historySize) {
                history.removeFirst();
            }
        }
    }

    @Override
    public Optional<LoopCheckpoint> latest(String runId) {
        Deque<LoopCheckpoint> history = checkpoints.get(runId);
        if (history == null) {
            return Optional.empty();
        }
        synchronized (history) {
            return Optional.ofNullable(history.peekLast());
        }
    }

    @Override
    public void delete(String runId) {
        checkpoints.remove(runId);
    }

    /** Test/debug helper returning a snapshot ordered oldest to newest. */
    public List<LoopCheckpoint> history(String runId) {
        Deque<LoopCheckpoint> history = checkpoints.get(runId);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /**
     * Evicts entries not updated since {@code cutoff}, to bound memory in long-running processes.
     */
    public int evictExpired(Instant cutoff) {
        List<String> expired =
                checkpoints.entrySet().stream()
                        .filter(entry -> isExpired(entry.getValue(), cutoff))
                        .map(entry -> entry.getKey())
                        .toList();
        expired.forEach(this::delete);
        return expired.size();
    }

    private boolean isExpired(Deque<LoopCheckpoint> history, Instant cutoff) {
        synchronized (history) {
            LoopCheckpoint newest = history.peekLast();
            return newest == null || newest.updatedAt().isBefore(cutoff);
        }
    }
}
