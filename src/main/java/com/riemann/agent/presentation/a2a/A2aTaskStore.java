package com.riemann.agent.presentation.a2a;

import com.riemann.agent.presentation.a2a.protocol.Task;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A 任务的内存存储，支撑 {@code tasks/get} 与 {@code tasks/cancel}，
 * 并在 {@code message/send}、{@code message/stream} 完成后回填最终 Task。
 *
 * <p>本地演示用内存实现；多实例部署时各实例仅持有自身任务的局部视图（任务 id 含上下文，
 * 对端通常带 taskId 回查同一实例），生产环境可替换为 Redis 等共享存储。</p>
 */
@Component
public class A2aTaskStore {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public void put(Task task) {
        tasks.put(task.id(), task);
    }

    public Optional<Task> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /** 对已有任务做原子更新（用于 cancel）。 */
    public Optional<Task> computeIfPresent(String id, java.util.function.Function<Task, Task> fn) {
        return Optional.ofNullable(tasks.computeIfPresent(id, (k, v) -> fn.apply(v)));
    }
}
