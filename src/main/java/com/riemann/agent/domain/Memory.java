package com.riemann.agent.domain;

import java.time.Instant;

/**
 * 长期记忆（Long-term Memory）领域对象：跨会话持久化、按用户维度组织的稳定事实。
 *
 * <p>与短期记忆（当前会话历史）区分：短期记忆由对话历史自动提供；长期记忆需要 Agent
 * 主动调用 save_memory 写入，并在后续每一轮通过召回（recall）重新注入上下文，
 * 从而实现“跨会话记住用户偏好 / 重要结论”。</p>
 */
public record Memory(String id, String userId, String content, Instant createdAt) {
}
