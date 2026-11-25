package com.riemann.agent.infrastructure.state;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans for idempotency and outbox support. */
@Configuration
public class StateReliabilityConfig {

    @Bean
    public IdempotencyGuard idempotencyGuard(StateReliabilityProperties properties) {
        return new InMemoryIdempotencyGuard(properties.getIdempotencyTtl());
    }

    @Bean
    public OutboxStore outboxStore(StateReliabilityProperties properties) {
        return new InMemoryOutboxStore(properties.getOutboxCapacity());
    }
}
