package com.riemann.agent.application;

import com.riemann.agent.infrastructure.state.ApprovalStore;
import com.riemann.agent.infrastructure.state.ApprovalStore.ApprovalMetadata;
import com.riemann.agent.infrastructure.state.ApprovalStore.Claim;
import com.riemann.agent.infrastructure.state.ApprovalStore.ClaimStatus;
import com.riemann.agent.infrastructure.state.CommandBus;
import com.riemann.agent.infrastructure.state.LocalApprovalStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Human-in-the-loop approvals. Metadata is shared through {@link ApprovalStore}; the executable
 * action stays on the pod that created it. When another replica receives approve/reject, {@link
 * CommandBus} routes the decision to the owning pod and carries the result back.
 */
@Service
public class ApprovalService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_PENDING = 500;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);

    private final ApprovalStore store;
    private final CommandBus commandBus;
    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();

    public ApprovalService() {
        this(new LocalApprovalStore(), (CommandBus) null);
    }

    @Autowired
    public ApprovalService(ApprovalStore store, ObjectProvider<CommandBus> commandBus) {
        this(store, commandBus.getIfAvailable());
    }

    public ApprovalService(ApprovalStore store, CommandBus commandBus) {
        this.store = store;
        this.commandBus = commandBus;
    }

    private record PendingAction(Supplier<Mono<String>> action, Instant expiresAt) {}

    public record ApprovalView(String id, String tool, String description, String status) {}

    /** Registers a pending action and returns its approval id. */
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
        Instant expires = Instant.now().plus(TTL);
        store.save(new ApprovalMetadata(id, userId, conversationId, tool, description, expires));
        actions.put(id, new PendingAction(action, expires));
        evictIfNeeded();
        return id;
    }

    /** Only the creator can see a pending approval. */
    public Optional<ApprovalView> view(String id, String userId) {
        return store.view(id)
                .filter(meta -> meta.userId().equals(userId))
                .map(
                        meta ->
                                new ApprovalView(
                                        meta.id(), meta.tool(), meta.description(), "pending"));
    }

    /** Consumes the approval and executes the side effect, locally or on the owning pod. */
    public Mono<String> approve(String id, String userId) {
        Claim claim = store.claim(id, userId);
        requireClaimed(claim);
        PendingAction pending = actions.remove(id);
        if (pending != null) {
            return pending.action().get();
        }
        if (commandBus != null) {
            return commandBus.executeApprovalOnOwner(id, userId, COMMAND_TIMEOUT);
        }
        return Mono.error(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "审批所属实例不可用，请稍后重试"));
    }

    /** Discards the action without executing any side effect. */
    public Mono<String> reject(String id, String userId) {
        Claim claim = store.claim(id, userId);
        requireClaimed(claim);
        actions.remove(id);
        return Mono.just("已拒绝执行：" + claim.metadata().description());
    }

    /** Executes an action that was claimed on another replica. */
    public Mono<String> executeClaimed(String id, String userId) {
        PendingAction pending = actions.remove(id);
        if (pending == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "审批动作不在当前实例"));
        }
        return pending.action().get();
    }

    private static void requireClaimed(Claim claim) {
        if (claim.status() == ClaimStatus.NOT_FOUND) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批不存在或已过期");
        }
        if (claim.status() == ClaimStatus.FORBIDDEN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权审批此操作");
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        Instant now = Instant.now();
        actions.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
    }

    private void evictIfNeeded() {
        if (actions.size() <= MAX_PENDING) {
            return;
        }
        actions.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt()))
                .limit(actions.size() - MAX_PENDING)
                .toList()
                .forEach(
                        e -> {
                            actions.remove(e.getKey(), e.getValue());
                            store.remove(e.getKey());
                        });
    }
}
