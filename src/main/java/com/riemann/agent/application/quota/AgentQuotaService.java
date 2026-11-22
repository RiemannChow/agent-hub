package com.riemann.agent.application.quota;

import com.riemann.agent.infrastructure.state.LocalQuotaState;
import com.riemann.agent.infrastructure.state.QuotaState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-user concurrency and daily token budget. The counting strategy is pluggable: the default
 * keeps the original in-process counters, {@code agent.state.backend=redis} shares them across
 * replicas so N pods no longer grant N times the quota.
 *
 * <p>Concurrency permits must be released in pairs: callers use {@code doFinally} so normal
 * completion, failure and client cancellation all return the permit.
 */
@Component
public class AgentQuotaService {

    private static final Logger log = LoggerFactory.getLogger(AgentQuotaService.class);

    private final AgentQuotaProperties props;
    private final QuotaState state;

    public AgentQuotaService(AgentQuotaProperties props) {
        this(props, new LocalQuotaState());
    }

    @Autowired
    public AgentQuotaService(AgentQuotaProperties props, QuotaState state) {
        this.props = props;
        this.state = state;
    }

    /**
     * Reserves one turn, checking the daily token budget first.
     *
     * @throws QuotaExceededException when the budget is exhausted or concurrency is over the limit
     */
    public void acquire(String userId) {
        if (!props.isEnabled()) {
            return;
        }
        int result =
                state.acquire(
                        userId, props.getDailyTokenLimit(), props.getMaxConcurrentTurnsPerUser());
        if (result == 1) {
            long limit = props.getDailyTokenLimit();
            throw new QuotaExceededException(
                    QuotaExceededException.Kind.DAILY_TOKENS,
                    "今日 token 用量已达上限（" + limit + "），请明天再试，或联系管理员提高 agent.quota.daily-token-limit。");
        }
        if (result == 2) {
            int max = props.getMaxConcurrentTurnsPerUser();
            throw new QuotaExceededException(
                    QuotaExceededException.Kind.CONCURRENCY,
                    "同时进行的对话已达上限（" + max + " 个），请等当前回答结束或点「停止」后再发。");
        }
    }

    /** Returns a concurrency permit. Safe to call more times than acquired. */
    public void release(String userId) {
        if (props.isEnabled()) {
            state.release(userId);
        }
    }

    /** Adds tokens used by a completed model call. */
    public void recordTokens(String userId, long tokens) {
        if (!props.isEnabled() || tokens <= 0) {
            return;
        }
        long limit = props.getDailyTokenLimit();
        long before = limit > 0 ? state.tokensUsedToday(userId) : 0;
        long total = state.recordTokens(userId, tokens, limit);
        if (limit > 0 && total >= limit && before < limit) {
            log.warn("用户 {} 今日 token 用量已达上限：{} / {}", userId, total, limit);
        }
    }

    public long tokensUsedToday(String userId) {
        return state.tokensUsedToday(userId);
    }

    public int concurrentTurns(String userId) {
        return state.concurrentTurns(userId);
    }
}
