package com.riemann.agent.presentation.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A2A 任务状态。
 * 注意协议线格式用连字符 {@code input-required}，枚举名用下划线，需双向映射。
 */
public enum TaskState {
    submitted,
    working,
    input_required("input-required"),
    completed,
    canceled,
    failed,
    unknown;

    private final String wire;

    TaskState() {
        this.wire = name();
    }

    TaskState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static TaskState from(String s) {
        if (s == null) {
            return unknown;
        }
        for (TaskState ts : values()) {
            if (ts.wire.equals(s) || ts.name().equals(s)) {
                return ts;
            }
        }
        return unknown;
    }
}
