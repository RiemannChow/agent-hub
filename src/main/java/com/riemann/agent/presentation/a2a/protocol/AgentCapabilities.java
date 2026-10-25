package com.riemann.agent.presentation.a2a.protocol;

/**
 * A2A 智能体能力声明。
 * streaming=true 表示支持 {@code message/stream}（SSE）。
 */
public record AgentCapabilities(boolean streaming, boolean pushNotifications, boolean stateTransitionHistory) {

    public static AgentCapabilities defaults() {
        return new AgentCapabilities(true, false, false);
    }
}
