package com.riemann.agent.domain;

import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Repository Port（端口），定义在 Domain 层。 由 Infrastructure 层提供具体实现（SeekDB / MongoDB）。 */
public interface ConversationRepository {

    Mono<Void> save(Conversation conversation);

    /** 增量追加本回合产生的若干消息（用户/工具调用/工具结果/助手），避免整聚合并重插导致重复。 */
    Mono<Void> appendMessages(ConversationId id, List<Message> messages);

    Mono<Conversation> findById(ConversationId id);

    /**
     * Load a conversation only when it belongs to {@code userId}.
     *
     * <p>Default implementations filter on {@link Conversation#userId()} after loading. The check
     * is deliberately an error rather than an empty result: {@code chat} treats an empty result as
     * "new conversation", so silently hiding a foreign row would let a caller append messages to
     * someone else's conversation instead of being rejected.
     */
    default Mono<Conversation> findByIdForUser(ConversationId id, String userId) {
        if (userId == null || userId.isBlank()) {
            return Mono.error(new IllegalArgumentException("userId is required"));
        }
        return findById(id)
                .flatMap(
                        conv ->
                                userId.equals(conv.userId())
                                        ? Mono.just(conv)
                                        : Mono.error(
                                                new AccessDeniedException(
                                                        "Conversation "
                                                                + id.value()
                                                                + " does not belong to the caller")));
    }

    Flux<Conversation> findByUserId(String userId);

    Mono<Void> updateAgentState(ConversationId id, AgentState state);
}
