package com.riemann.agent.presentation.a2a.protocol;

import java.util.List;
import java.util.Map;

/** A2A 任务：一次 {@code message/send} 的异步执行单元。 含当前状态、已生成产物、对话历史（含本次输入消息）与自定义元数据。 */
@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record Task(
        String id,
        String contextId,
        TaskStatus status,
        List<Artifact> artifacts,
        List<Message> history,
        Map<String, Object> metadata,
        List<TaskStateTransition> stateTransitionHistory) {

    /** Backwards-compatible constructor for tasks without a transition history. */
    public Task(
            String id,
            String contextId,
            TaskStatus status,
            List<Artifact> artifacts,
            List<Message> history,
            Map<String, Object> metadata) {
        this(id, contextId, status, artifacts, history, metadata, null);
    }

    /** Appends one transition, keeping earlier states for replay and audit. */
    public Task withTransition(TaskStateTransition transition) {
        List<TaskStateTransition> updated = new java.util.ArrayList<>();
        if (stateTransitionHistory != null) {
            updated.addAll(stateTransitionHistory);
        }
        updated.add(transition);
        return new Task(id, contextId, status, artifacts, history, metadata, List.copyOf(updated));
    }

    /** Replaces only the status while preserving the recorded transitions. */
    public Task withStatus(TaskStatus newStatus, TaskStateTransition transition) {
        Task changed =
                new Task(
                        id,
                        contextId,
                        newStatus,
                        artifacts,
                        history,
                        metadata,
                        stateTransitionHistory);
        return transition == null ? changed : changed.withTransition(transition);
    }
}
