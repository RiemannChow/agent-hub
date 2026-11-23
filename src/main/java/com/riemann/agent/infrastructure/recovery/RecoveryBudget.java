package com.riemann.agent.infrastructure.recovery;

import java.util.HashMap;
import java.util.Map;

/**
 * 单轮恢复预算：按类别、工具、整轮三个维度计数，硬上限防止无限重试。
 *
 * <p>每轮一个实例（与 TurnTokenBudget 一致）；另有带容量上限的静态 Map 供工具层按 conversationId 取用， 避免把计数塞进单例导致跨用户串号。
 */
public class RecoveryBudget {

    private final RecoveryProperties props;
    private final Map<ErrorTaxonomy.Category, Integer> byCategory = new HashMap<>();
    private final Map<String, Integer> byTool = new HashMap<>();
    private int total;

    public RecoveryBudget(RecoveryProperties props) {
        this.props = props;
    }

    /** 预算检查结论。 */
    public record Verdict(boolean allowed, String reason) {
        public static Verdict ok() {
            return new Verdict(true, "");
        }

        public static Verdict exhausted(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** 记录一次恢复尝试前的检查。 */
    public Verdict check(ErrorTaxonomy.Category category, String tool) {
        if (!props.isEnabled()) {
            return Verdict.ok();
        }
        if (total >= props.getMaxRecoveriesPerTurn()) {
            return Verdict.exhausted(
                    "turn recovery budget exhausted ("
                            + total
                            + "/"
                            + props.getMaxRecoveriesPerTurn()
                            + ")");
        }
        int cat = byCategory.getOrDefault(category, 0);
        if (cat >= props.getMaxAttemptsPerCategory()) {
            return Verdict.exhausted(
                    "category " + category + " attempt limit reached (" + cat + ")");
        }
        int t = byTool.getOrDefault(safe(tool), 0);
        if (t >= props.getMaxAttemptsPerTool()) {
            return Verdict.exhausted("tool " + safe(tool) + " attempt limit reached (" + t + ")");
        }
        return Verdict.ok();
    }

    /** 记一次恢复尝试。 */
    public void record(ErrorTaxonomy.Category category, String tool) {
        byCategory.merge(category, 1, Integer::sum);
        byTool.merge(safe(tool), 1, Integer::sum);
        total++;
    }

    public int used() {
        return total;
    }

    public int limit() {
        return props.getMaxRecoveriesPerTurn();
    }

    private static String safe(String tool) {
        return tool == null ? "unknown" : tool;
    }
}
