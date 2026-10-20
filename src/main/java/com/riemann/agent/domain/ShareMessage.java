package com.riemann.agent.domain;

/**
 * 分享快照中的单条消息（与对话消息一一对应，但已与领域实体解耦）。
 */
public record ShareMessage(
        String type,
        String content,
        String toolName,
        String arguments
) {
}
