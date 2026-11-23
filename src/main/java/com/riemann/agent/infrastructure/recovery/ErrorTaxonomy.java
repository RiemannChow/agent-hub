package com.riemann.agent.infrastructure.recovery;

import org.springframework.stereotype.Component;

/** 把工具/框架错误信息分类成可决策的类别。纯函数，便于完整单测。 */
@Component
public class ErrorTaxonomy {

    public enum Category {
        TRANSIENT,
        PERMANENT,
        POLICY_DENIED,
        VERIFICATION_FAILED,
        BUDGET_EXCEEDED,
        CANCELLED,
        UNKNOWN
    }

    /** 依据错误文本分类（大小写不敏感）。顺序：先特例，后通用。 */
    public Category classify(String message) {
        if (message == null || message.isBlank()) {
            return Category.UNKNOWN;
        }
        String m = message.toLowerCase();
        if (m.contains("策略拒绝") || m.contains("policy denied") || m.contains("denied by policy")) {
            return Category.POLICY_DENIED;
        }
        if (m.contains("结果验证")
                || m.contains("verification failed")
                || m.contains("not verifiable")) {
            return Category.VERIFICATION_FAILED;
        }
        if (m.contains("token 用量已达上限") || m.contains("budget exceeded") || m.contains("quota")) {
            return Category.BUDGET_EXCEEDED;
        }
        if (m.contains("取消") || m.contains("cancel") || m.contains("interrupt")) {
            return Category.CANCELLED;
        }
        if (m.contains("timeout")
                || m.contains("timed out")
                || m.contains("超时")
                || m.contains("rate limit")
                || m.contains("429")
                || m.contains("connection reset")
                || m.contains("connection refused")
                || m.contains("temporarily unavailable")) {
            return Category.TRANSIENT;
        }
        if (m.contains("sql 执行失败")
                || m.contains("syntax")
                || m.contains("does not exist")
                || m.contains("unknown column")
                || m.contains("table") && m.contains("not found")
                || m.contains("invalid parameter")
                || m.contains("参数")) {
            return Category.PERMANENT;
        }
        return Category.UNKNOWN;
    }
}
