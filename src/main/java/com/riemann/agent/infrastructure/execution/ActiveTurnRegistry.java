package com.riemann.agent.infrastructure.execution;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「当前正在跑的是哪一轮」。
 *
 * <p><b>为什么需要它：</b>AgentScope 的 {@code ReActAgent} 是单例 Bean，而 {@code interrupt()}
 * 的中断标志并不在 Agent 实例上，而是按 <b>(userId, sessionId)</b> 存在各自的 {@code AgentState}
 * 里（实测验证：{@code agent.interrupt(u, s)} 只会置位对应 state 的 {@code InterruptControl}，
 * 其它会话不受影响）。因此要中断某一轮，必须知道该轮当时用的 userId 与 sessionId。</p>
 *
 * <p>而本项目的 sessionId 是「每轮唯一」的（{@code conversationId + "-" + UUID}，用于隔离
 * Agent 内部状态），客户端只持有 conversationId，所以这里做一层
 * conversationId → (userId, sessionId) 的映射，供停止接口反查。</p>
 */
@Component
public class ActiveTurnRegistry {

    /** 一轮正在执行的对话。 */
    public record ActiveTurn(String conversationId, String userId, String sessionId, Instant startedAt) {
    }

    private final Map<String, ActiveTurn> byConversation = new ConcurrentHashMap<>();

    /** 轮次开始时登记。同一会话若已有在跑的轮次，以新轮次为准（旧轮次结束时不会误删新的）。 */
    public void register(ActiveTurn turn) {
        byConversation.put(turn.conversationId(), turn);
    }

    /**
     * 轮次结束时注销。
     *
     * <p>用 {@code remove(key, value)} 做「比对后删除」：同一会话并发两轮时，
     * 先结束的旧轮次不会把新轮次的登记删掉。</p>
     */
    public void unregister(ActiveTurn turn) {
        byConversation.remove(turn.conversationId(), turn);
    }

    public Optional<ActiveTurn> find(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        return Optional.ofNullable(byConversation.get(conversationId));
    }

    /** 当前在跑的轮次数量（供 metrics 暴露）。 */
    public int activeCount() {
        return byConversation.size();
    }
}
