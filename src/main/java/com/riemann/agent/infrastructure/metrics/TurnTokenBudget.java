package com.riemann.agent.infrastructure.metrics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单轮 token 预算（{@code agent.quota.max-tokens-per-turn}）的进程内追踪器。
 *
 * <p>每个对话轮次各自持有一个实例：累计本轮所有模型调用的输入 + 输出 token， 首次越过上限时通过 {@link #record(long)} 的返回值通知调用方中断后续工具/模型调用。
 * 与 {@code AgentQuotaService} 的「每用户每日预算」互补——后者挡长期累积， 本类挡「一次 ReAct 失控循环把当天额度烧光」。
 *
 * <p>{@code limit <= 0} 表示不限制（默认），此时 {@link #record(long)} 永远返回 false， 原有行为完全不变，golden-set 评测无需改断言。
 */
public final class TurnTokenBudget {

    private final long limit;
    private final AtomicLong used = new AtomicLong();
    private final AtomicBoolean exceeded = new AtomicBoolean();

    public TurnTokenBudget(long limit) {
        this.limit = Math.max(0L, limit);
    }

    /**
     * 累加一次模型调用的 token 用量。
     *
     * @return true 仅当本次调用使累计用量首次越过上限（调用方据此中断本轮）
     */
    public boolean record(long tokens) {
        if (tokens <= 0) {
            return false;
        }
        long total = used.addAndGet(tokens);
        if (limit > 0 && total > limit) {
            return exceeded.compareAndSet(false, true);
        }
        return false;
    }

    public long used() {
        return used.get();
    }

    public long limit() {
        return limit;
    }

    public boolean exceeded() {
        return limit > 0 && exceeded.get();
    }

    public boolean enabled() {
        return limit > 0;
    }
}
