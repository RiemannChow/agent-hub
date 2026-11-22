package com.riemann.agent.infrastructure.state;

/** Replay guard for signed A2A requests; must be shared across replicas. */
public interface NonceStore {

    /**
     * @return false when this exact signature was already registered inside the window.
     */
    boolean register(String signature, long maxAgeSeconds);
}
