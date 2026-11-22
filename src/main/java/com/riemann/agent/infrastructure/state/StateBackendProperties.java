package com.riemann.agent.infrastructure.state;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Shared-state backend selection ({@code agent.state.*}). */
@Component
@ConfigurationProperties(prefix = "agent.state")
public class StateBackendProperties {

    /** Local keeps the previous in-process behaviour and is the default. */
    private Backend backend = Backend.LOCAL;

    /** Namespace prefix for all Redis keys and pub/sub channels. */
    private String keyPrefix = "agent-hub";

    /** Maximum wait for a Redis command on an otherwise synchronous API. */
    private Duration commandTimeout = Duration.ofSeconds(3);

    public enum Backend {
        LOCAL,
        REDIS
    }

    public Backend getBackend() {
        return backend;
    }

    public void setBackend(Backend backend) {
        this.backend = backend;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }
}
