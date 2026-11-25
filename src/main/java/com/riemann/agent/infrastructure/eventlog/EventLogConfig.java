package com.riemann.agent.infrastructure.eventlog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the event log store. */
@Configuration
public class EventLogConfig {

    @Bean
    public EventLogStore eventLogStore(EventLogProperties properties) {
        return new InMemoryEventLogStore(properties.getCapacityPerRun());
    }
}
