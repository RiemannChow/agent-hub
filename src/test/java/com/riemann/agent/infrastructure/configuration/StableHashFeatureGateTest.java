package com.riemann.agent.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StableHashFeatureGateTest {

    @Test
    void returnsDefaultWhenRuleIsMissing() {
        StableHashFeatureGate gate = new StableHashFeatureGate(new FeatureGateProperties());

        FeatureDecision decision =
                gate.evaluate("missing", FeatureContext.of("u1", "c1", "r1"), "false");

        assertFalse(decision.isEnabled());
        assertEquals("default", decision.matchedRule());
    }

    @Test
    void userAllowlistWins() {
        FeatureGateProperties properties = new FeatureGateProperties();
        FeatureRuleConfig rule = new FeatureRuleConfig();
        rule.setValue("true");
        rule.setUserAllowlist(List.of("alice"));
        properties.getFlags().put("flag", rule);
        StableHashFeatureGate gate = new StableHashFeatureGate(properties);

        assertTrue(
                gate.evaluate("flag", FeatureContext.of("alice", "c1", "r1"), "false").isEnabled());
        assertFalse(
                gate.evaluate("flag", FeatureContext.of("bob", "c1", "r1"), "false").isEnabled());
        assertEquals(
                "user-allowlist",
                gate.evaluate("flag", FeatureContext.of("alice", "c1", "r1"), "false")
                        .matchedRule());
    }

    @Test
    void percentageBucketingIsStable() {
        FeatureGateProperties properties = new FeatureGateProperties();
        FeatureRuleConfig rule = new FeatureRuleConfig();
        rule.setValue("true");
        rule.setPercentage(100);
        properties.getFlags().put("flag", rule);
        StableHashFeatureGate gate = new StableHashFeatureGate(properties);
        FeatureContext context = FeatureContext.of("alice", "c1", "r1");

        assertEquals(
                gate.evaluate("flag", context, "false").value(),
                gate.evaluate("flag", context, "false").value());
        assertTrue(gate.evaluate("flag", context, "false").isEnabled());
        assertEquals("percentage", gate.evaluate("flag", context, "false").matchedRule());
    }

    @Test
    void environmentMismatchFallsBackToDefault() {
        FeatureGateProperties properties = new FeatureGateProperties();
        FeatureRuleConfig rule = new FeatureRuleConfig();
        rule.setValue("true");
        rule.setPercentage(100);
        rule.setEnvironment("prod");
        properties.getFlags().put("flag", rule);
        StableHashFeatureGate gate = new StableHashFeatureGate(properties);

        assertFalse(
                gate.evaluate("flag", new FeatureContext("alice", "c1", "r1", "local"), "false")
                        .isEnabled());
    }
}
