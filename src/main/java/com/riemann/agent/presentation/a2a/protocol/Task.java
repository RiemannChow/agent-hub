package com.riemann.agent.presentation.a2a.protocol;

import java.util.List;
import java.util.Map;

/**
 * A2A 任务：一次 {@code message/send} 的异步执行单元。
 * 含当前状态、已生成产物、对话历史（含本次输入消息）与自定义元数据。
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record Task(
        String id,
        String contextId,
        TaskStatus status,
        List<Artifact> artifacts,
        List<Message> history,
        Map<String, Object> metadata
) {
}
