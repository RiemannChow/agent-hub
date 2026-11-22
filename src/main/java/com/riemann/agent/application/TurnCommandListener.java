package com.riemann.agent.application;

import com.riemann.agent.domain.AgentOrchestrationService;
import com.riemann.agent.infrastructure.state.CommandBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Interrupts turns that are running on this replica when another pod receives a stop request. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class TurnCommandListener {

    public TurnCommandListener(CommandBus bus, AgentOrchestrationService orchestration) {
        bus.onCancelCommand(orchestration::cancel);
    }
}
