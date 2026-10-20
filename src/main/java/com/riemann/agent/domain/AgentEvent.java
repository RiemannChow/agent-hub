package com.riemann.agent.domain;

/**
 * 领域层统一的 Agent 事件（SSE 载荷）。
 * 五种事件全部为 record：Thinking / ToolCalling / ToolResult / Token / Completed。
 */
public sealed interface AgentEvent {

    record Thinking(String content) implements AgentEvent {}

    record ToolCalling(String toolName, String arguments) implements AgentEvent {}

    record ToolResult(String toolName, String result) implements AgentEvent {}

    record Token(String delta) implements AgentEvent {}

    record Completed(String conversationId) implements AgentEvent {}
}
