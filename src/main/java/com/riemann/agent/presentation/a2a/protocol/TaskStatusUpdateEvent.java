package com.riemann.agent.presentation.a2a.protocol;

/**
 * A2A {@code message/stream} 流式事件之一：任务状态变更。
 * 客户端依据携带 {@code status} 还是 {@code artifact} 来区分两类事件。
 */
public record TaskStatusUpdateEvent(String contextId, String taskId, TaskStatus status) {
}
