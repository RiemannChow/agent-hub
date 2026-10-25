package com.riemann.agent.presentation.a2a.protocol;

/**
 * A2A 任务状态快照：状态枚举 + 可选描述 + 时间戳。
 */
public record TaskStatus(TaskState state, String message, String timestamp) {

    public static TaskStatus of(TaskState state) {
        return new TaskStatus(state, null, now());
    }

    public static TaskStatus of(TaskState state, String message) {
        return new TaskStatus(state, message, now());
    }

    private static String now() {
        return java.time.Instant.now().toString();
    }
}
