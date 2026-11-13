package com.riemann.agent.presentation.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.infrastructure.a2a.A2aTaskStoreProperties;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import com.riemann.agent.presentation.a2a.protocol.TaskStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A task belongs to the verified peer that created it. Before issue #25, {@code tasks/get} and
 * {@code tasks/cancel} were keyed only by a client-supplied id, so any peer could read another
 * peer's full LLM answer or kill its running turn.
 */
class A2aTaskStoreOwnerTest {

    private static A2aTaskStore store() {
        return new A2aTaskStore(new A2aTaskStoreProperties());
    }

    private static Task task(String id) {
        return new Task(
                id, "ctx-1", TaskStatus.of(TaskState.completed), List.of(), List.of(), Map.of());
    }

    @Test
    void onlyTheOwnerCanReadATask() {
        A2aTaskStore store = store();
        store.put(task("t1"), "conv-1", "agent-a");

        assertThat(store.get("t1", "agent-a")).isPresent();
        assertThat(store.get("t1", "agent-b")).isEmpty();
        assertThat(store.get("t1", null)).isEmpty();
    }

    @Test
    void onlyTheOwnerCanResolveTheConversationId() {
        A2aTaskStore store = store();
        store.put(task("t1"), "conv-1", "agent-a");

        assertThat(store.conversationIdOf("t1", "agent-a")).contains("conv-1");
        assertThat(store.conversationIdOf("t1", "agent-b")).isEmpty();
    }

    @Test
    void foreignCallerCannotUpdateOrCancelATask() {
        A2aTaskStore store = store();
        store.put(task("t1"), "conv-1", "agent-a");
        AtomicBoolean fnCalled = new AtomicBoolean();

        assertThat(
                        store.computeIfPresent(
                                "t1",
                                "agent-b",
                                t -> {
                                    fnCalled.set(true);
                                    return t;
                                }))
                .isEmpty();
        assertThat(fnCalled).isFalse();
        assertThat(store.computeIfPresent("t1", "agent-a", t -> t)).isPresent();
    }

    @Test
    void missingOwnerIsNotStored() {
        A2aTaskStore store = store();
        store.put(task("t1"), "conv-1", null);
        assertThat(store.size()).isZero();
    }

    @Test
    void overwritingATaskKeepsTheOriginalOwner() {
        A2aTaskStore store = store();
        store.put(task("t1"), "conv-1", "agent-a");
        store.put(task("t1"), "conv-2", "agent-b");

        assertThat(store.get("t1", "agent-b")).isPresent();
        assertThat(store.get("t1", "agent-a")).isEmpty();
        assertThat(store.conversationIdOf("t1", "agent-b")).contains("conv-2");
    }
}
