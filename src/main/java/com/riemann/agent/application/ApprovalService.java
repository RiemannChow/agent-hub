package com.riemann.agent.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 人在回路审批：把有副作用的工具调用挂起，等登录用户显式批准/拒绝后才真正执行。
 *
 * <p>工具本身在 ReAct 循环内是同步产出结果的，无法停下来等用户点击，因此采用两阶段协议： 工具第一次调用返回一张 {@code type=approval} 的 A2UI 卡片（含
 * approvalId），并把真正的动作 注册到这里；用户在前端点「批准」才执行动作。审批与会话归属绑定，别的账号无法代批。
 *
 * <p>实现是进程内内存（带 TTL 与容量上限），与配额/沙箱状态一致——多实例部署需要换 Redis。
 */
@Service
public class ApprovalService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_PENDING = 500;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(
            String id,
            String userId,
            String conversationId,
            String tool,
            String description,
            Instant expiresAt,
            Supplier<Mono<String>> action) {

        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public record ApprovalView(String id, String tool, String description, String status) {}

    /** 注册一个待审批动作，返回 approvalId。 */
    public String request(
            String userId,
            String conversationId,
            String tool,
            String description,
            Supplier<Mono<String>> action) {
        if (userId == null || userId.isBlank() || action == null) {
            throw new IllegalArgumentException("userId and action are required");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Pending entry =
                new Pending(id, userId, conversationId, tool, description, now.plus(TTL), action);
        pending.put(id, entry);
        evictIfNeeded();
        return id;
    }

    /** 查看审批（仅创建者可见）。 */
    public Optional<ApprovalView> view(String id, String userId) {
        Pending p = live(id);
        if (p == null || !p.userId().equals(userId)) {
            return Optional.empty();
        }
        return Optional.of(new ApprovalView(p.id(), p.tool(), p.description(), "pending"));
    }

    /** 批准后执行动作。动作失败时审批已被消费，避免重复执行副作用。 */
    public Mono<String> approve(String id, String userId) {
        Pending p = requireOwner(id, userId);
        pending.remove(id, p);
        return p.action().get();
    }

    /** 拒绝：丢弃动作，不执行任何副作用。 */
    public Mono<String> reject(String id, String userId) {
        Pending p = requireOwner(id, userId);
        pending.remove(id, p);
        return Mono.just("已拒绝执行：" + p.description());
    }

    private Pending requireOwner(String id, String userId) {
        Pending p = live(id);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批不存在或已过期");
        }
        if (!p.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权审批此操作");
        }
        return p;
    }

    private Pending live(String id) {
        if (id == null) {
            return null;
        }
        Pending p = pending.get(id);
        if (p == null) {
            return null;
        }
        if (p.expired()) {
            pending.remove(id, p);
            return null;
        }
        return p;
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        pending.entrySet().removeIf(e -> e.getValue().expired());
    }

    private void evictIfNeeded() {
        if (pending.size() <= MAX_PENDING) {
            return;
        }
        pending.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt()))
                .limit(pending.size() - MAX_PENDING)
                .toList()
                .forEach(e -> pending.remove(e.getKey(), e.getValue()));
    }
}
