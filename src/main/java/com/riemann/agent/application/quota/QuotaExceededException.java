package com.riemann.agent.application.quota;

import java.util.Map;

/**
 * 超出配额/并发限制时抛出。
 *
 * <p>message 会经由 SSE 的 {@code error} 事件原样展示给用户，所以要写成「用户能看懂、
 * 知道该怎么办」的话，而不是内部术语。</p>
 */
public class QuotaExceededException extends RuntimeException {

    public enum Kind {
        /** 同一用户并发轮次超限。 */
        CONCURRENCY,
        /** 当日 token 预算用尽。 */
        DAILY_TOKENS
    }

    private final Kind kind;

    public QuotaExceededException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 供 SSE error 事件使用的结构化字段。 */
    public Map<String, Object> details() {
        return Map.of("kind", kind.name());
    }
}
