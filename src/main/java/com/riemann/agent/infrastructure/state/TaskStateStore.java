package com.riemann.agent.infrastructure.state;

import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Storage strategy behind {@code A2aTaskStore}; owner checks belong to the implementation. */
public interface TaskStateStore {

    void put(Task task, String conversationId, String owner);

    Optional<Task> get(String id, String owner);

    Optional<String> conversationIdOf(String id, String owner);

    Optional<Task> computeIfPresent(String id, String owner, Function<Task, Task> update);

    List<Task> list(String owner, String contextId, TaskState state, int limit, int offset);

    int count(String owner, String contextId, TaskState state);

    int size();

    void sweep();
}
