package com.riemann.agent.infrastructure.state;

/** Storage strategy behind {@code AgentQuotaService}. */
public interface QuotaState {

    /**
     * Atomically checks the daily token budget and takes one concurrency slot.
     *
     * @return 0 = allowed, 1 = daily budget exhausted, 2 = concurrency limit reached
     */
    int acquire(String userId, long dailyTokenLimit, int maxConcurrentTurns);

    void release(String userId);

    /** Adds tokens to today's counter and returns the new total. */
    long recordTokens(String userId, long tokens, long dailyTokenLimit);

    long tokensUsedToday(String userId);

    int concurrentTurns(String userId);
}
