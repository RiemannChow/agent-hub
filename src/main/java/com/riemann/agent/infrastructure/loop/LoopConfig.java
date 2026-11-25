package com.riemann.agent.infrastructure.loop;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans shared by all loop implementations. */
@Configuration
public class LoopConfig {

    @Bean
    public LoopCheckpointStore loopCheckpointStore(AgentLoopProperties properties) {
        return new InMemoryLoopCheckpointStore(properties.getCheckpointHistorySize());
    }
}
