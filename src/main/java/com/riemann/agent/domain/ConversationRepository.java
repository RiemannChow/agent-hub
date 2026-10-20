package com.riemann.agent.domain;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Repository Port（端口），定义在 Domain 层。
 * 由 Infrastructure 层提供具体实现（SeekDB / MongoDB）。
 */
public interface ConversationRepository {

    Mono<Void> save(Conversation conversation);

    /** 增量追加本回合产生的若干消息（用户/工具调用/工具结果/助手），避免整聚合并重插导致重复。 */
    Mono<Void> appendMessages(ConversationId id, List<Message> messages);

    Mono<Conversation> findById(ConversationId id);

    Flux<Conversation> findByUserId(String userId);

    Mono<Void> updateAgentState(ConversationId id, AgentState state);
}
