package com.riemann.agent.infrastructure.toolkit.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryToolAuditStoreTest {

    @Test
    void recordsAndQueriesByRun() {
        InMemoryToolAuditStore store = new InMemoryToolAuditStore(4);
        store.record(entry("run-1", "seek_sql_query"));
        store.record(entry("run-1", "save_memory"));
        store.record(entry("run-2", "seek_sql_query"));

        assertEquals(2, store.queryByRun("run-1").size());
        assertEquals(1, store.queryByRun("run-2").size());
        assertTrue(store.queryByRun("missing").isEmpty());
    }

    @Test
    void boundsEntriesPerRun() {
        InMemoryToolAuditStore store = new InMemoryToolAuditStore(2);
        store.record(entry("run-1", "tool-1"));
        store.record(entry("run-1", "tool-2"));
        store.record(entry("run-1", "tool-3"));

        assertEquals(2, store.queryByRun("run-1").size());
        assertEquals("tool-2", store.queryByRun("run-1").get(0).toolName());
    }

    private static ToolInvocationAudit entry(String runId, String toolName) {
        return new ToolInvocationAudit(
                runId,
                "step-1",
                "user-1",
                "conv-1",
                toolName,
                "1.0.0",
                "ALLOW",
                true,
                12L,
                "",
                Instant.now());
    }
}
