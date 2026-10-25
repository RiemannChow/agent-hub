package com.riemann.agent.presentation.a2a;

import com.riemann.agent.presentation.a2a.protocol.AgentCard;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露 A2A Agent Card：GET /.well-known/agent.json。
 * 对端智能体/客户端据此发现本智能体的能力、技能与 A2A 端点。
 */
@RestController
public class A2aAgentCardController {

    private final A2aAgentCardProvider cardProvider;

    public A2aAgentCardController(A2aAgentCardProvider cardProvider) {
        this.cardProvider = cardProvider;
    }

    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentCard agentCard() {
        return cardProvider.build();
    }
}
