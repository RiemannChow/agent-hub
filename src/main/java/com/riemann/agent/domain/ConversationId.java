package com.riemann.agent.domain;

import java.util.Objects;

/**
 * 会话标识 —— 值对象（Value Object）。
 * 不可变，且禁止空值。
 */
public record ConversationId(String value) {
    public ConversationId {
        Objects.requireNonNull(value, "conversationId.value must not be null");
    }
}
