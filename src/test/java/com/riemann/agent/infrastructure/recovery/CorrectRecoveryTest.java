package com.riemann.agent.infrastructure.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.infrastructure.recovery.ErrorTaxonomy.Category;
import com.riemann.agent.infrastructure.recovery.RecoveryPolicy.Strategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorrectRecoveryTest {

    private final RecoveryProperties props = new RecoveryProperties();
    private final ErrorTaxonomy taxonomy = new ErrorTaxonomy();
    private final RecoveryPolicy policy = new RecoveryPolicy(props);

    @Test
    void classifiesCommonErrors() {
        assertThat(taxonomy.classify("connection timeout")).isEqualTo(Category.TRANSIENT);
        assertThat(taxonomy.classify("403 rate limit")).isEqualTo(Category.TRANSIENT);
        assertThat(taxonomy.classify("SQL 执行失败：unknown column x")).isEqualTo(Category.PERMANENT);
        assertThat(taxonomy.classify("【策略拒绝】tool denied")).isEqualTo(Category.POLICY_DENIED);
        assertThat(taxonomy.classify("【结果验证】verification failed"))
                .isEqualTo(Category.VERIFICATION_FAILED);
        assertThat(taxonomy.classify("本轮 token 用量已达上限")).isEqualTo(Category.BUDGET_EXCEEDED);
        assertThat(taxonomy.classify("请求已取消")).isEqualTo(Category.CANCELLED);
        assertThat(taxonomy.classify("weird")).isEqualTo(Category.UNKNOWN);
    }

    @Test
    void mapsCategoriesToStrategies() {
        RecoveryBudget b = new RecoveryBudget(props);
        assertThat(policy.decide(Category.TRANSIENT, "t", b).strategy()).isEqualTo(Strategy.RETRY);
        assertThat(policy.decide(Category.PERMANENT, "t", b).strategy())
                .isEqualTo(Strategy.COMPENSATE);
        assertThat(policy.decide(Category.POLICY_DENIED, "t", b).strategy())
                .isEqualTo(Strategy.ESCALATE);
        assertThat(policy.decide(Category.VERIFICATION_FAILED, "t", b).strategy())
                .isEqualTo(Strategy.CONTINUE);
        assertThat(policy.decide(Category.CANCELLED, "t", b).strategy()).isEqualTo(Strategy.STOP);
        assertThat(policy.decide(Category.BUDGET_EXCEEDED, "t", b).strategy())
                .isEqualTo(Strategy.ESCALATE);
    }

    @Test
    void budgetForcesEscalationWhenExhausted() {
        // 单独验证「整轮」上限：放宽 per-category / per-tool，让 total 先触发
        RecoveryProperties p = new RecoveryProperties();
        p.setMaxAttemptsPerCategory(100);
        p.setMaxAttemptsPerTool(100);
        RecoveryPolicy broadPolicy = new RecoveryPolicy(p);
        RecoveryBudget b = new RecoveryBudget(p);
        for (int i = 0; i < p.getMaxRecoveriesPerTurn(); i++) {
            RecoveryPolicy.Decision d = broadPolicy.decide(Category.TRANSIENT, "t" + i, b);
            assertThat(d.strategy()).isEqualTo(Strategy.RETRY);
            b.record(Category.TRANSIENT, "t" + i);
        }
        RecoveryPolicy.Decision forced = broadPolicy.decide(Category.TRANSIENT, "t", b);
        assertThat(forced.strategy()).isEqualTo(Strategy.ESCALATE);
        assertThat(forced.reason()).contains("budget");
    }

    @Test
    void budgetCapsPerCategoryAndPerTool() {
        RecoveryBudget b = new RecoveryBudget(props);
        for (int i = 0; i < props.getMaxAttemptsPerCategory(); i++) {
            b.check(Category.TRANSIENT, "same-tool");
            b.record(Category.TRANSIENT, "same-tool");
        }
        assertThat(policy.decide(Category.TRANSIENT, "same-tool", b).strategy())
                .isEqualTo(Strategy.ESCALATE);
    }

    @Test
    void idempotencyKeyIsDeterministicAndSorted() {
        IdempotencyKeys keys = new IdempotencyKeys();
        String a = keys.of("conv", "turn", "tool", Map.of("b", 2, "a", 1));
        String b = keys.of("conv", "turn", "tool", Map.of("a", 1, "b", 2));
        assertThat(a).isEqualTo(b).hasSize(16);
        assertThat(keys.of("conv", "turn2", "tool", Map.of("a", 1, "b", 2))).isNotEqualTo(a);
        assertThat(keys.of("conv", "turn", "other-tool", Map.of("a", 1, "b", 2))).isNotEqualTo(a);
    }

    @Test
    void turnRecoveryEmitsDecisionsAndDetectsEscalation() {
        TurnRecovery tr = new TurnRecovery(taxonomy, policy, new RecoveryBudget(props));
        List<AgentEvent.Recovery> r1 =
                tr.observe(new AgentEvent.ToolResult("t", "SQL 执行失败：unknown column"));
        assertThat(r1).hasSize(1);
        assertThat(r1.get(0).strategy()).isEqualTo(Strategy.COMPENSATE.name());
        List<AgentEvent.Recovery> r2 = tr.observe(new AgentEvent.ToolResult("t", "【策略拒绝】denied"));
        assertThat(r2.get(0).strategy()).isEqualTo(Strategy.ESCALATE.name());
        assertThat(tr.escalated()).isTrue();
        assertThat(tr.decisions()).hasSize(2);
    }

    @Test
    void normalToolResultProducesNoDecision() {
        TurnRecovery tr = new TurnRecovery(taxonomy, policy, new RecoveryBudget(props));
        assertThat(tr.observe(new AgentEvent.ToolResult("t", "total=550"))).isEmpty();
    }
}
