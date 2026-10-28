package com.riemann.agent.infrastructure.execution;

import io.agentscope.core.ReActAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 停止某一轮正在执行的对话。
 *
 * <p>调用链：conversationId → {@link ActiveTurnRegistry} 反查 (userId, sessionId)
 * → {@code ReActAgent.interrupt(userId, sessionId)}。</p>
 *
 * <p><b>中断的边界要说清楚</b>：AgentScope 在 ReAct 循环的每一轮迭代开始处检查中断标志
 * （{@code reasoning()} 里的 {@code checkInterrupted()}）。所以：</p>
 * <ul>
 *   <li>已经发出的这一次模型调用 / 工具调用<b>不会被强行掐断</b>，它会自然返回；</li>
 *   <li>返回后循环立刻停止，不再发起下一轮推理，因此不会继续烧 token；</li>
 *   <li>框架会用 {@code handleInterrupt} 收尾，客户端仍能收到一条结束事件。</li>
 * </ul>
 *
 * <p>这对「用户点停止」「用户关掉标签页」两个场景都足够：真正的成本来自后续的
 * 多次迭代，而不是当前那一次调用。</p>
 */
@Component
public class TurnCancellationService {

    private static final Logger log = LoggerFactory.getLogger(TurnCancellationService.class);

    private final ObjectProvider<ReActAgent> agentProvider;
    private final ActiveTurnRegistry registry;

    public TurnCancellationService(ObjectProvider<ReActAgent> agentProvider, ActiveTurnRegistry registry) {
        this.agentProvider = agentProvider;
        this.registry = registry;
    }

    /**
     * @return true 表示确实找到并中断了一轮在跑的对话；false 表示该会话当前没有在跑的轮次
     *         （已结束 / 从未开始 / 当前 profile 没有 Agent）。
     */
    public boolean cancel(String conversationId) {
        Optional<ActiveTurnRegistry.ActiveTurn> turn = registry.find(conversationId);
        if (turn.isEmpty()) {
            log.debug("取消请求未命中：会话 {} 当前没有在跑的轮次", conversationId);
            return false;
        }
        ReActAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            log.warn("取消请求无法执行：当前 profile 下没有 ReActAgent Bean");
            return false;
        }
        ActiveTurnRegistry.ActiveTurn t = turn.get();
        // 只置位该 (userId, sessionId) 的中断标志，不会影响同一 Agent 上的其它会话
        agent.interrupt(t.userId(), t.sessionId());
        log.info("已请求中断会话 {} 的当前轮次（session={}，已运行 {} ms）",
                conversationId, t.sessionId(), System.currentTimeMillis() - t.startedAt().toEpochMilli());
        return true;
    }
}
