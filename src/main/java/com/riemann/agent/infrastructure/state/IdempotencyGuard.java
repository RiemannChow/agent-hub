package com.riemann.agent.infrastructure.state;

/** Guards against duplicate side effects for the same idempotency key. */
public interface IdempotencyGuard {

    /**
     * @return true if the key was unseen and is now claimed; false if it was already claimed.
     */
    boolean tryAcquire(String key);
}
