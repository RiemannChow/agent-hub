package com.riemann.agent.domain;

import java.time.Instant;

/**
 * 会话消息 —— sealed interface。
 * 仅允许四种实现：用户消息、助手消息、工具调用、工具结果。
 */
public sealed interface Message {

    String conversationId();

    Instant timestamp();

    record UserMessage(String conversationId, String content, Instant timestamp) implements Message {}

    record AssistantMessage(String conversationId, String content, Instant timestamp) implements Message {}

    record ToolCallMessage(String conversationId, String toolName, String arguments, Instant timestamp) implements Message {}

    record ToolResultMessage(String conversationId, String toolName, String result, Instant timestamp) implements Message {}
}
