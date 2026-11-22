package com.riemann.agent.infrastructure.state;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The original in-process quota counters; kept as the default backend. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "local", matchIfMissing = true)
public class LocalQuotaState implements QuotaState {

    private final Map<String, UserUsage> usages = new ConcurrentHashMap<>();

    private static final class UserUsage {
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicLong tokens = new AtomicLong();
        volatile LocalDate day = LocalDate.now();
    }

    @Override
    public int acquire(String userId, long dailyTokenLimit, int maxConcurrentTurns) {
        UserUsage u = usageOf(userId);
        rollIfNewDay(u);
        if (dailyTokenLimit > 0 && u.tokens.get() >= dailyTokenLimit) {
            return 1;
        }
        int now = u.concurrent.incrementAndGet();
        if (maxConcurrentTurns > 0 && now > maxConcurrentTurns) {
            u.concurrent.decrementAndGet();
            return 2;
        }
        return 0;
    }

    @Override
    public void release(String userId) {
        UserUsage u = usages.get(userId);
        if (u != null) {
            u.concurrent.updateAndGet(cur -> cur > 0 ? cur - 1 : 0);
        }
    }

    @Override
    public long recordTokens(String userId, long tokens, long dailyTokenLimit) {
        if (tokens <= 0) {
            return tokensUsedToday(userId);
        }
        UserUsage u = usageOf(userId);
        rollIfNewDay(u);
        return u.tokens.addAndGet(tokens);
    }

    @Override
    public long tokensUsedToday(String userId) {
        UserUsage u = usages.get(userId);
        if (u == null) {
            return 0L;
        }
        rollIfNewDay(u);
        return u.tokens.get();
    }

    @Override
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
            u.tokens.set(0);
            u.day = today;
        }
    }
}
