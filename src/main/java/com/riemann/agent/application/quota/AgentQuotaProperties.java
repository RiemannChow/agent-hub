package com.riemann.agent.application.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流与配额配置（{@code agent.quota.*}）。
 *
 * <p>目的很具体：{@code /api/agent/chat} 之前既没有「每用户并发上限」也没有「每日 token 预算」， 一个用户开十个标签页就能把上游模型网关打到限流，而成本无从约束。
 */
@Component
@ConfigurationProperties(prefix = "agent.quota")
public class AgentQuotaProperties {

    /** 是否启用限流与配额。关掉后行为与改造前一致。 */
    private boolean enabled = true;

    /** 单个用户同时允许的在跑轮次上限。 */
    private int maxConcurrentTurnsPerUser = 2;

    /** 单个用户每日 token 预算（输入 + 输出）。<=0 表示不限制。按自然日滚动重置。 */
    private long dailyTokenLimit = 500_000L;

    /**
     * 单轮 token 上限（输入 + 输出）。默认 0 = 不限制，保持已有行为。
     *
     * <p>与每日配额互补：每日配额挡「长期累积」，本项挡「一次 ReAct 失控循环把当天额度烧光」。 在 AgentScope 事件流上按模型调用累计，超限即中断本轮并给用户明确提示。
     */
    private long maxTokensPerTurn = 0L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxConcurrentTurnsPerUser() {
        return maxConcurrentTurnsPerUser;
    }

    public void setMaxConcurrentTurnsPerUser(int maxConcurrentTurnsPerUser) {
        this.maxConcurrentTurnsPerUser = maxConcurrentTurnsPerUser;
    }

    public long getDailyTokenLimit() {
        return dailyTokenLimit;
    }

    public void setDailyTokenLimit(long dailyTokenLimit) {
        this.dailyTokenLimit = dailyTokenLimit;
    }

    public long getMaxTokensPerTurn() {
        return maxTokensPerTurn;
    }

    public void setMaxTokensPerTurn(long maxTokensPerTurn) {
        this.maxTokensPerTurn = maxTokensPerTurn;
    }
}
