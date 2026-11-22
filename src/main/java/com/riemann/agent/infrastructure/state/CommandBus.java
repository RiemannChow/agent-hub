package com.riemann.agent.infrastructure.state;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;

/**
 * Routes a command to the replica that owns the resource and carries the result back.
 *
 * <p>Only used when {@code agent.state.backend=redis}; the local backend executes everything in
 * process and never touches this interface.
 */
public interface CommandBus {

    /** Registers the handler that executes an already-claimed approval on its owning pod. */
    void onApprovalCommand(BiConsumer<String, String> handler);

    /** Registers the handler that interrupts a turn running on this pod. */
    void onCancelCommand(Consumer<String> handler);

    /** Sends the command to the owning pod and waits for its result. */
    Mono<String> executeApprovalOnOwner(String approvalId, String userId, Duration timeout);

    void publishApprovalResult(String approvalId, String result, boolean failed);

    void publishCancel(String conversationId);

    record Result(String value, boolean failed) {}
}
