package com.riemann.agent.infrastructure.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryLoopCheckpointStoreTest {

    @Test
    void returnsLatestCheckpointForRun() {
        InMemoryLoopCheckpointStore store = new InMemoryLoopCheckpointStore(4);
        store.save(checkpoint("run-1", 1, Instant.parse("2026-11-25T10:00:00Z")));
        store.save(checkpoint("run-1", 2, Instant.parse("2026-11-25T10:00:01Z")));

        assertTrue(store.latest("run-1").isPresent());
        assertEquals(2, store.latest("run-1").orElseThrow().stepNo());
        assertTrue(store.latest("missing").isEmpty());
    }

    @Test
    void boundsHistoryPerRun() {
        InMemoryLoopCheckpointStore store = new InMemoryLoopCheckpointStore(2);
        for (int i = 1; i <= 5; i++) {
            store.save(
                    checkpoint("run-2", i, Instant.parse("2026-11-25T10:00:00Z").plusSeconds(i)));
        }

        assertEquals(2, store.history("run-2").size());
        assertEquals(4, store.history("run-2").get(0).stepNo());
        assertEquals(5, store.history("run-2").get(1).stepNo());
    }

    @Test
    void deletesAndEvictsExpiredRuns() {
        InMemoryLoopCheckpointStore store = new InMemoryLoopCheckpointStore(4);
        store.save(checkpoint("old", 1, Instant.parse("2026-11-20T10:00:00Z")));
        store.save(checkpoint("fresh", 1, Instant.parse("2026-11-25T10:00:00Z")));

        assertEquals(1, store.evictExpired(Instant.parse("2026-11-24T00:00:00Z")));
        assertTrue(store.latest("old").isEmpty());
        assertTrue(store.latest("fresh").isPresent());

        store.delete("fresh");
        assertTrue(store.latest("fresh").isEmpty());
    }

    private static LoopCheckpoint checkpoint(String runId, int stepNo, Instant updatedAt) {
        return new LoopCheckpoint(
                runId,
                stepNo,
                LoopCheckpoint.Phase.THINK,
                "RUNNING",
                Map.of("messages", "ref-" + stepNo),
                10 * stepNo,
                updatedAt);
    }
}
