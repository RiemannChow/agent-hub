package com.riemann.agent.domain;

import java.time.Instant;

/**
 * Agent 运行态 —— 值对象。
 * 记录 Agent 当前状态、推理步数，以及（若有）SeekDB 的 COW 沙箱库名。
 */
public record AgentState(
        String agentId,
        String status,
        long stepCount,
        String sandboxDbName,
        Instant updatedAt
) {

    public static AgentState initial(String agentId) {
        return new AgentState(agentId, "IDLE", 0L, null, Instant.now());
    }

    public AgentState withStatus(String status) {
        return new AgentState(agentId, status, stepCount, sandboxDbName, Instant.now());
    }

    public AgentState withSandbox(String sandboxDbName) {
        return new AgentState(agentId, status, stepCount, sandboxDbName, Instant.now());
    }

    public AgentState incStep() {
        return new AgentState(agentId, status, stepCount + 1, sandboxDbName, Instant.now());
    }
}
