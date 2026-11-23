package com.riemann.agent.infrastructure.recovery;

import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.infrastructure.recovery.ErrorTaxonomy.Category;
import com.riemann.agent.infrastructure.recovery.RecoveryPolicy.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * 单轮恢复追踪器：从领域事件流里识别工具错误，按分类选策略并产出 {@link AgentEvent.Recovery}。
 *
 * <p>每轮一个实例，状态不跨用户共享。框架的重试仍由 AgentScope 的 ExecutionConfig 执行； 这里不重复重试，而是把「错误类别 → 策略 →
 * 预算」显式化并可观测，超预算时强制 ESCALATE。
 */
public class TurnRecovery {

    private static final List<String> ERROR_MARKERS =
            List.of("失败", "error", "exception", "denied", "拒绝", "unavailable");

    private final ErrorTaxonomy taxonomy;
    private final RecoveryPolicy policy;
    private final RecoveryBudget budget;
    private final List<AgentEvent.Recovery> decisions = new ArrayList<>();

    public TurnRecovery(ErrorTaxonomy taxonomy, RecoveryPolicy policy, RecoveryBudget budget) {
        this.taxonomy = taxonomy;
        this.policy = policy;
        this.budget = budget;
    }

    /** 观察一条领域事件；若它是工具错误或提示类错误，产出一条 Recovery 事件（可能为多条，调用方应发射全部）。 */
    public List<AgentEvent.Recovery> observe(AgentEvent event) {
        if (event instanceof AgentEvent.ToolResult t && looksLikeError(t.result())) {
            return decide(t.result(), t.toolName());
        }
        if (event instanceof AgentEvent.Notice n && looksLikeError(n.message())) {
            return decide(n.message(), "turn");
        }
        return List.of();
    }

    private List<AgentEvent.Recovery> decide(String message, String tool) {
        Category category = taxonomy.classify(message);
        RecoveryPolicy.Decision d = policy.decide(category, tool, budget);
        if (d.attemptRecorded()) {
            budget.record(category, tool);
        }
        AgentEvent.Recovery recovery =
                new AgentEvent.Recovery(
                        category.name(),
                        d.strategy().name(),
                        tool,
                        budget.used(),
                        budget.limit(),
                        d.reason());
        decisions.add(recovery);
        return List.of(recovery);
    }

    /** 轮末汇总：是否发生过 ESCALATE（供上层决定是否追加人工接管提示）。 */
    public boolean escalated() {
        return decisions.stream().anyMatch(d -> Strategy.ESCALATE.name().equals(d.strategy()));
    }

    public List<AgentEvent.Recovery> decisions() {
        return List.copyOf(decisions);
    }

    private static boolean looksLikeError(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        return ERROR_MARKERS.stream().anyMatch(lower::contains);
    }
}
