package com.riemann.agent.infrastructure.toolkit;

import com.riemann.agent.domain.ScoredConversation;

/**
 * 工具层返回的结果结构（可被 AgentScope 序列化回模型）。
 */
public record ScoredResult(String conversationId, String title, String snippet, double score) {

    public static ScoredResult from(ScoredConversation c) {
        return new ScoredResult(c.conversationId(), c.title(), c.snippet(), c.score());
    }
}
