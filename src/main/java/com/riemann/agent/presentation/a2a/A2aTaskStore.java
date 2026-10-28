package com.riemann.agent.presentation.a2a;

import com.riemann.agent.infrastructure.a2a.A2aTaskStoreProperties;
import com.riemann.agent.presentation.a2a.protocol.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A2A 任务存储，支撑 {@code tasks/get} 与 {@code tasks/cancel}，
 * 并在 {@code message/send}、{@code message/stream} 完成后回填最终 Task。
 *
 * <h3>相比早期实现的三处修正</h3>
 * <ol>
 *   <li><b>带 TTL 与容量上限</b>：原实现是裸 {@code ConcurrentHashMap}，每个任务都攥着一轮对话的
 *       完整 artifacts（全文），永不释放——长跑进程内存只涨不跌。现在到期即失效，超出容量淘汰最旧。</li>
 *   <li><b>记录任务对应的会话 id</b>：{@code tasks/cancel} 之前只把状态改成 {@code canceled}，
 *       真正在跑的那一轮根本不知道被取消了，会继续烧 token 到 {@code turn-timeout}。
 *       有了 {@code conversationId}，取消才能落到 {@code AgentOrchestrationService.cancel()} 上。</li>
 *   <li><b>取消时保留原 artifacts/history</b>：{@code computeIfPresent} 只换状态、不动内容，
 *       客户端仍能取回取消前已产出的部分。</li>
 * </ol>
 *
 * <p>仍是内存实现：多实例部署时各实例只持有自身任务的局部视图（任务 id 由调用方回传，
 * 通常落在同一实例）。要跨实例可查，把本类换成 Redis/DB 实现即可，接口不变。</p>
 */
@Component
public class A2aTaskStore {

    private static final Logger log = LoggerFactory.getLogger(A2aTaskStore.class);

    private final A2aTaskStoreProperties props;
    private final Map<String, Entry> tasks = new ConcurrentHashMap<>();

    public A2aTaskStore(A2aTaskStoreProperties props) {
        this.props = props;
    }

    /** 一条任务记录：任务本体 + 它对应的本机会话 + 过期时间。 */
    private record Entry(Task task, String conversationId, Instant createdAt, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /** 写入/覆盖任务（{@code message/send} 与 {@code message/stream} 完成时回填）。 */
    public void put(Task task, String conversationId) {
        if (task == null || task.id() == null) {
            return;
        }
        Instant now = Instant.now();
        tasks.put(task.id(), new Entry(task, conversationId, now, now.plus(props.getTtl())));
        evictIfNeeded();
    }

    /** 取任务；已过期视为不存在（顺手清掉）。 */
    public Optional<Task> get(String id) {
        Entry e = tasks.get(id);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expired()) {
            tasks.remove(id, e);
            return Optional.empty();
        }
        return Optional.of(e.task());
    }

    /** 任务对应的本机会话 id（取消时用来定位真正在跑的那一轮）。 */
    public Optional<String> conversationIdOf(String id) {
        Entry e = tasks.get(id);
        if (e == null || e.expired()) {
            return Optional.empty();
        }
        return Optional.ofNullable(e.conversationId());
    }

    /** 对已有任务做原子更新（用于 cancel），保留会话关联与过期时间。 */
    public Optional<Task> computeIfPresent(String id, Function<Task, Task> fn) {
        Entry updated = tasks.computeIfPresent(id, (k, old) -> {
            Task next = fn.apply(old.task());
            return next == null ? old : new Entry(next, old.conversationId(), old.createdAt(), old.expiresAt());
        });
        return updated == null ? Optional.empty() : Optional.of(updated.task());
    }

    public int size() {
        return tasks.size();
    }

    /** 定期清理过期任务（每分钟）。放在调度线程上，不占请求线程。 */
    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        int before = tasks.size();
        tasks.entrySet().removeIf(e -> e.getValue().expired());
        int removed = before - tasks.size();
        if (removed > 0) {
            log.debug("[A2A] 清理过期任务 {} 个，剩余 {}", removed, tasks.size());
        }
    }

    /** 超出容量上限时按创建时间淘汰最旧的，保证内存有界。 */
    private void evictIfNeeded() {
        int max = props.getMaxEntries();
        if (max <= 0 || tasks.size() <= max) {
            return;
        }
        List<Map.Entry<String, Entry>> ordered = tasks.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().createdAt()))
                .toList();
        int toRemove = tasks.size() - max;
        for (int i = 0; i < toRemove && i < ordered.size(); i++) {
            tasks.remove(ordered.get(i).getKey(), ordered.get(i).getValue());
        }
        log.debug("[A2A] 任务数超过上限 {}，已淘汰最旧的 {} 条", max, toRemove);
    }
}
