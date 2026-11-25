package com.riemann.agent.infrastructure.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InMemoryOutboxStoreTest {

    @Test
    void enqueueAndMarkProcessed() {
        InMemoryOutboxStore store = new InMemoryOutboxStore(4);
        OutboxEvent event = OutboxEvent.of("conv-1", "MESSAGES_APPEND", "count=1");
        store.enqueue(event);

        assertEquals(1, store.size());
        assertEquals(event.id(), store.pending().get(0).id());

        store.markProcessed(event.id());
        assertTrue(store.pending().isEmpty());
    }

    @Test
    void boundsPendingEvents() {
        InMemoryOutboxStore store = new InMemoryOutboxStore(2);
        store.enqueue(OutboxEvent.of("conv-1", "A", "1"));
        store.enqueue(OutboxEvent.of("conv-1", "B", "2"));
        store.enqueue(OutboxEvent.of("conv-1", "C", "3"));

        assertEquals(2, store.size());
        assertEquals("B", store.snapshot().get(0).type());
        assertEquals("C", store.snapshot().get(1).type());
    }
}
