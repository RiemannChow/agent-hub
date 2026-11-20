package com.riemann.agent.presentation.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A {@code message/stream} 流式事件之一：任务状态变更。
 *
 * <p>按协议输出 {@code kind="status-update"} 与 {@code final}；{@code final=true} 表示该轮事件流的
 * 最后一条状态事件。保留旧三参构造函数，方便既有调用方继续编译。
 */
public record TaskStatusUpdateEvent(
        String kind,
        String contextId,
        String taskId,
        TaskStatus status,
        @JsonProperty("final") boolean isFinal) {

    public TaskStatusUpdateEvent(String contextId, String taskId, TaskStatus status) {
        this("status-update", contextId, taskId, status, false);
    }

    public static TaskStatusUpdateEvent last(String contextId, String taskId, TaskStatus status) {
        return new TaskStatusUpdateEvent("status-update", contextId, taskId, status, true);
    }
}
