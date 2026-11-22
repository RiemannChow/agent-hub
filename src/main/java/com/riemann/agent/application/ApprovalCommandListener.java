package com.riemann.agent.application;

import com.riemann.agent.infrastructure.state.CommandBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Executes approval decisions that were claimed on a different replica. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class ApprovalCommandListener {

    public ApprovalCommandListener(CommandBus bus, ApprovalService approvals) {
        bus.onApprovalCommand(
                (id, userId) ->
                        approvals
                                .executeClaimed(id, userId)
                                .subscribe(
                                        result -> bus.publishApprovalResult(id, result, false),
                                        error ->
                                                bus.publishApprovalResult(
                                                        id,
                                                        String.valueOf(error.getMessage()),
                                                        true)));
    }
}
