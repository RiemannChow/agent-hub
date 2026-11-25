package com.riemann.agent.infrastructure.toolkit.registry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded in-memory audit store; a DB implementation can replace it transparently. */
public class InMemoryToolAuditStore implements ToolAuditStore {

    private final int capacityPerRun;
    private final Map<String, Deque<ToolInvocationAudit>> byRun = new ConcurrentHashMap<>();

    public InMemoryToolAuditStore(int capacityPerRun) {
        if (capacityPerRun <= 0) {
            throw new IllegalArgumentException("capacityPerRun must be greater than 0");
        }
        this.capacityPerRun = capacityPerRun;
    }

    @Override
    public void record(ToolInvocationAudit entry) {
        Deque<ToolInvocationAudit> entries =
                byRun.computeIfAbsent(entry.runId(), key -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > capacityPerRun) {
                entries.removeFirst();
            }
        }
    }

    @Override
    public List<ToolInvocationAudit> queryByRun(String runId) {
        Deque<ToolInvocationAudit> entries = byRun.get(runId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }
}
