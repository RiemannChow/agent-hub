package com.riemann.agent.presentation.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A {@code message/stream} 流式事件之二：产物增量更新。 {@code append=true} 表示在已有产物上追加；{@code lastChunk=true}
 * 表示本产物已结束。
 *
 * <p>与状态事件一样输出协议要求的 {@code kind} 和 {@code final} 字段。
 */
public record TaskArtifactUpdateEvent(
        String kind,
        String contextId,
        String taskId,
        Artifact artifact,
        @JsonProperty("final") boolean isFinal) {

    public TaskArtifactUpdateEvent(String contextId, String taskId, Artifact artifact) {
        this("artifact-update", contextId, taskId, artifact, false);
    }

    public static TaskArtifactUpdateEvent last(String contextId, String taskId, Artifact artifact) {
        return new TaskArtifactUpdateEvent("artifact-update", contextId, taskId, artifact, true);
    }
}
