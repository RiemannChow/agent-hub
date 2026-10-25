package com.riemann.agent.presentation.a2a.protocol;

/**
 * A2A {@code message/stream} 流式事件之二：产物增量更新。
 * {@code append=true} 表示在已有产物上追加；{@code lastChunk=true} 表示本产物已结束。
 */
public record TaskArtifactUpdateEvent(String contextId, String taskId, Artifact artifact) {
}
