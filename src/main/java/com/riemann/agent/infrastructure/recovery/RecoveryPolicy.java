package com.riemann.agent.infrastructure.recovery;

import com.riemann.agent.infrastructure.recovery.ErrorTaxonomy.Category;
import com.riemann.agent.infrastructure.recovery.RecoveryBudget.Verdict;
import org.springframework.stereotype.Component;

/** 按错误类别选择恢复策略。纯决策，不做 IO。 */
@Component
public class RecoveryPolicy {

    public enum Strategy {
        RETRY,
        CONTINUE,
        COMPENSATE,
        ESCALATE,
        STOP
    }

    public record Decision(Strategy strategy, String reason, boolean attemptRecorded) {
        public static Decision of(Strategy s, String reason) {
            return new Decision(s, reason, s == Strategy.RETRY);
        }
    }

    private final RecoveryProperties props;

    public RecoveryPolicy(RecoveryProperties props) {
        this.props = props;
    }

    /**
     * 选择恢复策略。
     *
     * @param category 错误类别
     * @param tool 出错工具
     * @param budget 本轮预算
     */
    public Decision decide(Category category, String tool, RecoveryBudget budget) {
        if (!props.isEnabled() || category == null) {
            return Decision.of(Strategy.STOP, "correct disabled or unknown category");
        }
        switch (category) {
            case CANCELLED:
                return Decision.of(Strategy.STOP, "turn cancelled; no recovery");
            case BUDGET_EXCEEDED:
                return Decision.of(Strategy.ESCALATE, "budget exhausted; hand off to user");
            case POLICY_DENIED:
                return Decision.of(Strategy.ESCALATE, "policy denied; do not retry");
            case VERIFICATION_FAILED:
                return Decision.of(
                        Strategy.CONTINUE, "unverified data; continue with correction hint");
            case TRANSIENT:
                Verdict v = budget.check(category, tool);
                if (!v.allowed()) {
                    return Decision.of(Strategy.ESCALATE, v.reason());
                }
                return Decision.of(Strategy.RETRY, "transient error; bounded retry");
            case PERMANENT:
            case UNKNOWN:
            default:
                return Decision.of(
                        Strategy.COMPENSATE,
                        "permanent error; compensate partial effects and stop");
        }
    }
}
