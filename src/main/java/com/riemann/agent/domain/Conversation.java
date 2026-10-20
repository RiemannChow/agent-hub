package com.riemann.agent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 聚合根：会话（Conversation）。
 * 包含 会话标识、归属用户、行业人格、消息列表、Agent 运行态。
 * 不依赖任何框架注解（DDD 纯 Java）。
 */
public class Conversation {

    private final ConversationId id;
    private final String userId;
    private final AgentPersona persona;
    private final List<Message> messages;
    private AgentState agentState;
    private Instant updatedAt;

    public Conversation(ConversationId id, String userId, AgentPersona persona, AgentState agentState) {
        this.id = id;
        this.userId = userId;
        this.persona = persona;
        this.agentState = agentState != null ? agentState : AgentState.initial("seek-agent");
        // 默认用 AgentState 的时间；若仓储层能从 DB 的 updated_at 列回填（withUpdatedAt），
        // 则以真实落库时间为准，避免所有会话的“最后更新时间”都变成加载时刻而失去排序/展示意义。
        this.updatedAt = this.agentState.updatedAt();
        this.messages = new ArrayList<>();
    }

    public ConversationId id() { return id; }
    public String userId() { return userId; }
    public AgentPersona persona() { return persona; }
    public AgentState agentState() { return agentState; }

    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }

    /** 追加一条消息（返回自身，保持聚合内聚）。 */
    public Conversation addMessage(Message message) {
        this.messages.add(message);
        return this;
    }

    /** 更新 Agent 运行态。 */
    public Conversation updateState(AgentState state) {
        this.agentState = state;
        return this;
    }

    /** 用仓储层从 DB 的 updated_at 列回填真实落库时间，避免排序/展示失真。 */
    public Conversation withUpdatedAt(Instant updatedAt) {
        if (updatedAt != null) {
            this.updatedAt = updatedAt;
        }
        return this;
    }

    public Instant lastUpdated() {
        return updatedAt;
    }
}
