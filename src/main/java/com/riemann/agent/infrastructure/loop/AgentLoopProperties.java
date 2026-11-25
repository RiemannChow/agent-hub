package com.riemann.agent.infrastructure.loop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AgentLoop runtime properties.
 *
 * <p>This is the first step towards making the ReAct loop configurable instead of hard-coded in
 * {@code AgentConfig} / {@code OceanBaseAgentConfig}. Defaults preserve the current behaviour
 * exactly, so enabling this bean does not change any existing golden-set result.
 */
@Component
@ConfigurationProperties(prefix = "agent.loop")
public class AgentLoopProperties {

    /** Maximum number of ReAct iterations in one turn. */
    private int maxIters = 30;

    /** Upper bound of checkpoints kept per run by the in-memory store. */
    private int checkpointHistorySize = 16;

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        if (maxIters <= 0) {
            throw new IllegalArgumentException("agent.loop.max-iters must be greater than 0");
        }
        this.maxIters = maxIters;
    }

    public int getCheckpointHistorySize() {
        return checkpointHistorySize;
    }

    public void setCheckpointHistorySize(int checkpointHistorySize) {
        if (checkpointHistorySize <= 0) {
            throw new IllegalArgumentException(
                    "agent.loop.checkpoint-history-size must be greater than 0");
        }
        this.checkpointHistorySize = checkpointHistorySize;
    }
}
