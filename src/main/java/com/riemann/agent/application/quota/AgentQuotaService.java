package com.riemann.agent.application.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每用户并发上限 + 每日 token 预算。
 *
 * <p><b>为什么放在进程内：</b>本项目当前是单实例部署，用进程内计数最省事、无额外依赖。
 * 多实例部署时这份计数会按实例分裂（N 个实例 = N 倍额度），届时需要把
 * {@link UserUsage} 换成 Redis 计数器——接口（acquire/release/recordTokens）不需要变。</p>
 *
 * <p><b>并发许可必须成对释放</b>：调用方要在事件流的 {@code doFinally} 里 release，
 * 这样正常结束、异常、客户端取消三种终止路径都能归还许可，不会把用户永久锁死。</p>
 */
@Component
public class AgentQuotaService {

    private static final Logger log = LoggerFactory.getLogger(AgentQuotaService.class);

    private final AgentQuotaProperties props;
    private final Map<String, UserUsage> usages = new ConcurrentHashMap<>();

    public AgentQuotaService(AgentQuotaProperties props) {
        this.props = props;
    }

    /** 单用户的用量状态。 */
    private static final class UserUsage {
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicLong tokens = new AtomicLong();
        volatile LocalDate day = LocalDate.now();
    }

    /**
     * 申请开始一轮对话：先校验当日 token 预算，再占用一个并发名额。
     *
     * @throws QuotaExceededException 预算用尽或并发超限；此时不会占用名额
     */
    public void acquire(String userId) {
        if (!props.isEnabled()) return;
        UserUsage u = usageOf(userId);
        rollIfNewDay(u);

        long limit = props.getDailyTokenLimit();
        if (limit > 0 && u.tokens.get() >= limit) {
            throw new QuotaExceededException(QuotaExceededException.Kind.DAILY_TOKENS,
                    "今日 token 用量已达上限（" + limit + "），请明天再试，或联系管理员提高 agent.quota.daily-token-limit。");
        }

        int max = props.getMaxConcurrentTurnsPerUser();
        int now = u.concurrent.incrementAndGet();
        if (max > 0 && now > max) {
            u.concurrent.decrementAndGet();
            throw new QuotaExceededException(QuotaExceededException.Kind.CONCURRENCY,
                    "同时进行的对话已达上限（" + max + " 个），请等当前回答结束或点「停止」后再发。");
        }
    }

    /** 归还并发名额。可重复调用（不会减到负数）。 */
    public void release(String userId) {
        if (!props.isEnabled()) return;
        UserUsage u = usages.get(userId);
        if (u == null) return;
        int v = u.concurrent.updateAndGet(cur -> cur > 0 ? cur - 1 : 0);
        log.debug("释放并发名额：user={} 当前并发={}", userId, v);
    }

    /** 累加 token 用量（由事件流里的模型调用结束事件驱动）。 */
    public void recordTokens(String userId, long tokens) {
        if (!props.isEnabled() || tokens <= 0) return;
        UserUsage u = usageOf(userId);
        rollIfNewDay(u);
        long total = u.tokens.addAndGet(tokens);
        long limit = props.getDailyTokenLimit();
        if (limit > 0 && total >= limit && total - tokens < limit) {
            // 只在「刚好跨过阈值」时告警一次，避免每条消息刷屏
            log.warn("用户 {} 今日 token 用量已达上限：{} / {}", userId, total, limit);
        }
    }

    /** 当前用户今日已用 token（监控/排障用）。 */
    public long tokensUsedToday(String userId) {
        UserUsage u = usages.get(userId);
        if (u == null) return 0L;
        rollIfNewDay(u);
        return u.tokens.get();
    }

    /** 当前用户的在跑轮次数量。 */
    public int concurrentTurns(String userId) {
        UserUsage u = usages.get(userId);
        return u == null ? 0 : u.concurrent.get();
    }

    private UserUsage usageOf(String userId) {
        String key = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        return usages.computeIfAbsent(key, k -> new UserUsage());
    }

    private void rollIfNewDay(UserUsage u) {
        LocalDate today = LocalDate.now();
        if (!today.equals(u.day)) {
            // 自然日切换：清零当日用量（并发计数不清，因为它代表"此刻在跑"）
            u.tokens.set(0);
            u.day = today;
        }
    }
}
