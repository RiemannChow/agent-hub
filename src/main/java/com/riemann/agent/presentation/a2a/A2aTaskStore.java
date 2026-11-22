package com.riemann.agent.presentation.a2a;

import com.riemann.agent.infrastructure.a2a.A2aTaskStoreProperties;
import com.riemann.agent.infrastructure.state.LocalTaskStateStore;
import com.riemann.agent.infrastructure.state.TaskStateStore;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A2A task facade. The storage strategy is either the original bounded in-process map or a Redis
 * store shared by all replicas; owner checks are enforced by both implementations.
 */
@Component
public class A2aTaskStore {

    private final TaskStateStore store;

    public A2aTaskStore(A2aTaskStoreProperties props) {
        this(new LocalTaskStateStore(props));
    }

    @Autowired
    public A2aTaskStore(TaskStateStore store) {
        this.store = store;
    }

    public void put(Task task, String conversationId, String owner) {
        store.put(task, conversationId, owner);
    }

    public Optional<Task> get(String id, String owner) {
        return store.get(id, owner);
    }

    public Optional<String> conversationIdOf(String id, String owner) {
        return store.conversationIdOf(id, owner);
    }

    public Optional<Task> computeIfPresent(String id, String owner, Function<Task, Task> fn) {
        return store.computeIfPresent(id, owner, fn);
    }

    public List<Task> list(String owner, String contextId, TaskState state, int limit, int offset) {
        return store.list(owner, contextId, state, limit, offset);
    }

    public int count(String owner, String contextId, TaskState state) {
        return store.count(owner, contextId, state);
    }

    public int size() {
        return store.size();
    }

    public void sweep() {
        store.sweep();
    }
}
