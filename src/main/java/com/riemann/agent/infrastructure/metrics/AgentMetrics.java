package com.riemann.agent.infrastructure.metrics;

import com.riemann.agent.infrastructure.execution.ActiveTurnRegistry;
import io.agentscope.core.model.ChatUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Agent 运行指标（Micrometer）。
 *
 * <p>与 Langfuse 的分工：Langfuse 回答「这一次调用为什么慢」，Micrometer 回答
 * 「今天 P99 多少、工具失败率多少、一共烧了多少 token」。前者是逐条 trace，
 * 后者是可聚合、可告警的时间序列，缺一不可。</p>
 *
 * <p>指标清单（{@code /actuator/prometheus} 可抓）：</p>
 * <ul>
 *   <li>{@code agent.turns}（counter，tag: outcome=completed|cancelled|error）</li>
 *   <li>{@code agent.turn.duration}（timer，tag: outcome，含 p50/p95/p99）</li>
 *   <li>{@code agent.turns.active}（gauge，当前在跑的轮次）</li>
 *   <li>{@code agent.model.calls}（counter）</li>
 *   <li>{@code agent.tokens}（counter，tag: type=input|output|cached）</li>
 *   <li>{@code agent.tool.calls}（counter，tag: tool, state=SUCCESS|ERROR|...）</li>
 * </ul>
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry, ActiveTurnRegistry turnRegistry) {
        this.registry = registry;
        Gauge.builder("agent.turns.active", turnRegistry, ActiveTurnRegistry::activeCount)
                .description("当前正在执行的对话轮次数量")
                .register(registry);
    }

    /** 开始计时一轮对话。 */
    public Timer.Sample startTurn() {
        return Timer.start(registry);
    }

    /**
     * 结束计时并记一次轮次结果。
     *
     * @param outcome completed / cancelled / error
     */
    public void stopTurn(Timer.Sample sample, String outcome) {
        if (sample != null) {
            sample.stop(Timer.builder("agent.turn.duration")
                    .description("单轮对话端到端耗时")
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry));
        }
        Counter.builder("agent.turns")
                .description("对话轮次总数")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    /** 记录一次模型调用的 token 用量。 */
    public void recordModelUsage(ChatUsage usage) {
        Counter.builder("agent.model.calls").description("模型调用次数").register(registry).increment();
        if (usage == null) return;
        incrementTokens("input", usage.getInputTokens());
        incrementTokens("output", usage.getOutputTokens());
        incrementTokens("cached", usage.getCachedTokens());
    }

    /** 记录一次工具调用结果（用于算工具失败率）。 */
    public void recordToolCall(String toolName, String state) {
        Counter.builder("agent.tool.calls")
                .description("工具调用次数")
                .tag("tool", safeTag(toolName))
                .tag("state", safeTag(state))
                .register(registry)
                .increment();
    }

    /** 工具结果被截断/预算拦截的次数：高了说明该收紧工具的返回体量或查询条件。 */
    public void recordToolTruncated(String toolName) {
        Counter.builder("agent.tool.truncated")
                .description("工具结果被截断（单次上限或单轮预算）的次数")
                .tag("tool", safeTag(toolName))
                .register(registry)
                .increment();
    }

    /** 工具被熔断的次数：连续失败到阈值后不再回填真实错误。 */
    public void recordToolCircuitOpen(String toolName) {
        Counter.builder("agent.tool.circuit_open")
                .description("工具连续失败被熔断的次数")
                .tag("tool", safeTag(toolName))
                .register(registry)
                .increment();
    }

    private void incrementTokens(String type, int tokens) {
        if (tokens <= 0) return;
        Counter.builder("agent.tokens")
                .description("模型 token 用量")
                .tag("type", type)
                .register(registry)
                .increment(tokens);
    }

    /** 标签值不能为 null，也不能为空串（Prometheus 抓取会报错）。 */
    private static String safeTag(String v) {
        return (v == null || v.isBlank()) ? "unknown" : v;
    }

    /** 把 Reactor 的终止信号翻译成指标 tag 值。 */
    public static String outcomeOf(reactor.core.publisher.SignalType signal) {
        return switch (signal) {
            case ON_COMPLETE -> "completed";
            case CANCEL -> "cancelled";
            case ON_ERROR -> "error";
            default -> "other";
        };
    }
}
