package com.riemann.agent.infrastructure.metrics;

import com.riemann.agent.application.quota.AgentQuotaService;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.model.ChatUsage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 在框架事件流上挂一层「旁路计量」。
 *
 * <p>AgentScope 的 {@code streamEvents} 已经把「每次模型调用结束」「每次工具调用结束」作为
 * 强类型事件吐出来了，所以指标与 token 计费不需要再包 hook 或 middleware——直接在事件流上
 * 旁路记录即可，零侵入、也不会改变事件本身。</p>
 */
@Component
public class TurnTelemetry {

    private final AgentMetrics metrics;
    private final AgentQuotaService quota;

    public TurnTelemetry(AgentMetrics metrics, AgentQuotaService quota) {
        this.metrics = metrics;
        this.quota = quota;
    }

    /**
     * @param upstream 框架事件流（未经映射）
     * @param userId   本轮用户，用于累计 token 用量
     */
    public Flux<io.agentscope.core.event.AgentEvent> instrument(
            Flux<io.agentscope.core.event.AgentEvent> upstream, String userId) {
        return upstream.doOnNext(event -> {
            if (event instanceof ModelCallEndEvent model) {
                metrics.recordModelUsage(model.getUsage());
                if (model.getUsage() != null) {
                    quota.recordTokens(userId, model.getUsage().getTotalTokens());
                }
            } else if (event instanceof ToolResultEndEvent tool) {
                metrics.recordToolCall(tool.getToolCallName(),
                        tool.getState() == null ? "unknown" : tool.getState().name());
            }
        });
    }

    /**
     * 计量一次「边车」模型调用 —— 那些不走 ReActAgent 事件流、直接调 {@code model.stream(...)} 的调用，
     * 例如 {@code MemoryExtractor} 的事实抽取、{@code ContextWindowAssembler} 的历史摘要。
     *
     * <p><b>为什么需要它：</b>此前这两处调用绕过 {@link #instrument}，导致
     * {@code agent.tokens} / {@code agent.model.calls} 系统性少算约 1/3，每日 token 配额也漏计。
     * 它们的 {@code ChatResponse} 里同样带 {@link ChatUsage}，抽出来在这里统一记账即可，
     * 与事件流路径共用同一套指标与配额逻辑。</p>
     *
     * @param usage 该次调用最后一个 {@code ChatResponse} 携带的用量（可能为 null）
     * @param userId 调用归属的用户，用于累计每日 token 预算
     */
    public void recordModelCall(ChatUsage usage, String userId) {
        metrics.recordModelUsage(usage);
        if (usage != null) {
            quota.recordTokens(userId, usage.getTotalTokens());
        }
    }
}
