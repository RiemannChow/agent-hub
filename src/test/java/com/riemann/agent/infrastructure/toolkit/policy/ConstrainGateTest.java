package com.riemann.agent.infrastructure.toolkit.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.infrastructure.toolkit.policy.ConstrainGate.Decision;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstrainGateTest {

    private final ToolPolicyRegistry registry = new ToolPolicyRegistry();
    private final ConstrainGate gate = new ConstrainGate(registry);

    @Test
    void allowsReadOnlyToolWithValidArguments() {
        Decision d = gate.check("seek_sql_query", Map.of("sql", "select 1"), local());
        assertThat(d.decision()).isEqualTo(ToolPolicy.Decision.ALLOW);
    }

    @Test
    void deniesMissingRequiredParameter() {
        Decision d = gate.check("seek_sql_query", Map.of(), local());
        assertThat(d.decision()).isEqualTo(ToolPolicy.Decision.DENY);
        assertThat(d.reason()).contains("missing required parameter");
    }

    @Test
    void deniesExcessivelyLongArgument() {
        String huge = "x".repeat(5000);
        Decision d = gate.check("seek_sql_query", Map.of("sql", huge), local());
        assertThat(d.decision()).isEqualTo(ToolPolicy.Decision.DENY);
        assertThat(d.reason()).contains("max length");
    }

    @Test
    void asksForSensitiveTools() {
        Decision d = gate.check("save_memory", Map.of("content", "remember this"), local());
        assertThat(d.decision()).isEqualTo(ToolPolicy.Decision.ASK);
        assertThat(d.risk()).isEqualTo(ToolPolicy.Risk.HIGH);
    }

    @Test
    void deniesDelegatedCallBeyondDepth() {
        Decision d =
                gate.check(
                        "seek_sql_query",
                        Map.of("sql", "select 1"),
                        new com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext(
                                "u", "c", 9));
        assertThat(d.decision()).isEqualTo(ToolPolicy.Decision.DENY);
        assertThat(d.reason()).contains("delegation depth");
    }

    @Test
    void deniesWithoutCallerContext() {
        assertThat(gate.check("seek_sql_query", Map.of("sql", "select 1"), null).decision())
                .isEqualTo(ToolPolicy.Decision.DENY);
    }

    @Test
    void unknownToolIsDefaultDenied() {
        assertThat(gate.checkUnknown("never_registered", local()).decision())
                .isEqualTo(ToolPolicy.Decision.DENY);
        assertThat(gate.policyExists("never_registered")).isFalse();
    }

    @Test
    void configCanDenyABuiltInTool() {
        ToolPolicyRegistry.PolicyOverride override = new ToolPolicyRegistry.PolicyOverride();
        override.setDecision(ToolPolicy.Decision.DENY);
        registry.getPolicies().put("seek_sql_query", override);
        assertThat(gate.check("seek_sql_query", Map.of("sql", "select 1"), local()).decision())
                .isEqualTo(ToolPolicy.Decision.DENY);
    }

    @Test
    void disabledGateAllowsEverything() {
        registry.setEnabled(false);
        assertThat(gate.checkUnknown("anything", null).decision())
                .isEqualTo(ToolPolicy.Decision.ALLOW);
    }

    private static com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext local() {
        return new com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext(
                "user-a", "conv-1");
    }
}
