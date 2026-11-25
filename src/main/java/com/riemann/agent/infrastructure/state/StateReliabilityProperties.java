package com.riemann.agent.infrastructure.state;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the state reliability slice. */
@Component
@ConfigurationProperties(prefix = "agent.state.reliability")
public class StateReliabilityProperties {

    /** How long an idempotency key remains claimed. */
    private Duration idempotencyTtl = Duration.ofMinutes(10);

    /** Maximum number of pending outbox events kept in memory. */
    private int outboxCapacity = 512;

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public int getOutboxCapacity() {
        return outboxCapacity;
    }

    public void setOutboxCapacity(int outboxCapacity) {
        if (outboxCapacity <= 0) {
            throw new IllegalArgumentException(
                    "agent.state.reliability.outbox-capacity must be greater than 0");
        }
        this.outboxCapacity = outboxCapacity;
    }
}
