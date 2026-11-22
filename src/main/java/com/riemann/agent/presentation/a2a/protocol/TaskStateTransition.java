package com.riemann.agent.presentation.a2a.protocol;

import java.time.Instant;

/** One entry of a task's A2A state transition history. */
public record TaskStateTransition(TaskState state, String timestamp, String message) {

    public static TaskStateTransition of(TaskState state, String message) {
        return new TaskStateTransition(state, Instant.now().toString(), message);
    }
}
