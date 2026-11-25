package com.riemann.agent.infrastructure.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InMemoryEventLogStoreTest {

    @Test
    void assignsMonotonicSequencePerRun() {
        InMemoryEventLogStore store = new InMemoryEventLogStore(8);
        store.append(EventEnvelope.of("run-1", "START", "trace-1", "v1", ""));
        EventEnvelope second =
                store.append(EventEnvelope.of("run-1", "TOOL_CALL", "trace-1", "v1", "{}"));

        assertEquals(2L, second.sequence());
        assertEquals(2, store.query("run-1").size());
        assertTrue(store.query("run-1", 2L).stream().allMatch(e -> e.sequence() >= 2L));
        assertTrue(store.query("missing").isEmpty());
    }

    @Test
    void boundsEventsPerRun() {
        InMemoryEventLogStore store = new InMemoryEventLogStore(2);
        for (int i = 0; i < 5; i++) {
            store.append(EventEnvelope.of("run-1", "STEP", "trace-1", "v1", String.valueOf(i)));
        }

        assertEquals(2, store.query("run-1").size());
        assertEquals(4L, store.query("run-1").get(0).sequence());
        assertEquals(5L, store.query("run-1").get(1).sequence());
    }
}
