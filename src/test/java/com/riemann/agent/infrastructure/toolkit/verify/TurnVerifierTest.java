package com.riemann.agent.infrastructure.toolkit.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.domain.AgentEvent;
import org.junit.jupiter.api.Test;

class TurnVerifierTest {

    private final VerificationGate gate = new VerificationGate(new VerificationProperties());

    @Test
    void groundedAnswerPasses() {
        TurnVerifier v = new TurnVerifier(new VerificationProperties(), gate);
        v.observe(new AgentEvent.ToolResult("seek_sql_query", "total=550 | fiction=240"));
        v.observe(new AgentEvent.Token("一共 550 本书，其中 240 本是 Fiction。"));
        AgentEvent.Verification verdict = v.conclusion();
        assertThat(verdict.grounded()).isTrue();
        assertThat(verdict.toolCalls()).isEqualTo(1);
    }

    @Test
    void ungroundedNumberIsFlagged() {
        TurnVerifier v = new TurnVerifier(new VerificationProperties(), gate);
        v.observe(new AgentEvent.ToolResult("seek_sql_query", "total=550"));
        v.observe(new AgentEvent.Token("一共 999 本书。"));
        AgentEvent.Verification verdict = v.conclusion();
        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.reason()).contains("999");
    }

    @Test
    void noToolCallsDoesNotFlagGreeting() {
        TurnVerifier v = new TurnVerifier(new VerificationProperties(), gate);
        v.observe(new AgentEvent.Token("你好，这里是电商问数助手。"));
        AgentEvent.Verification verdict = v.conclusion();
        assertThat(verdict.grounded()).isTrue();
        assertThat(verdict.toolCalls()).isZero();
    }

    @Test
    void thousandSeparatorAlignsWithAnswer() {
        TurnVerifier v = new TurnVerifier(new VerificationProperties(), gate);
        v.observe(new AgentEvent.ToolResult("seek_sql_query", "total=1,200"));
        v.observe(new AgentEvent.Token("一共 1200 本。"));
        assertThat(v.conclusion().grounded()).isTrue();
    }

    @Test
    void disabledVerifierAlwaysPasses() {
        VerificationProperties props = new VerificationProperties();
        props.setEnabled(false);
        TurnVerifier v = new TurnVerifier(props, gate);
        v.observe(new AgentEvent.ToolResult("t", "550"));
        v.observe(new AgentEvent.Token("9999"));
        assertThat(v.conclusion().grounded()).isTrue();
        assertThat(v.conclusion().reason()).contains("disabled");
    }
}
