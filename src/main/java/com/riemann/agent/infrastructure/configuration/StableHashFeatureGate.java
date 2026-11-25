package com.riemann.agent.infrastructure.configuration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Feature gate with stable percentage bucketing by userId (or runId as fallback). */
public class StableHashFeatureGate implements FeatureGate {

    private static final String DEFAULT_RULE = "default";

    private final FeatureGateProperties properties;

    public StableHashFeatureGate(FeatureGateProperties properties) {
        this.properties = properties;
    }

    @Override
    public FeatureDecision evaluate(String key, FeatureContext context, String defaultValue) {
        FeatureRuleConfig rule = properties.getFlags().get(key);
        if (rule == null) {
            return new FeatureDecision(defaultValue, DEFAULT_RULE);
        }
        FeatureContext ctx = context == null ? new FeatureContext(null, null, null, null) : context;
        if (!rule.getEnvironment().isBlank() && !rule.getEnvironment().equals(ctx.environment())) {
            return new FeatureDecision(defaultValue, DEFAULT_RULE);
        }
        if (contains(rule.getUserAllowlist(), ctx.userId())) {
            return new FeatureDecision(rule.getValue(), "user-allowlist");
        }
        if (contains(rule.getConversationAllowlist(), ctx.conversationId())) {
            return new FeatureDecision(rule.getValue(), "conversation-allowlist");
        }
        String identity = ctx.userId() == null ? ctx.runId() : ctx.userId();
        if (identity != null && bucket(key, identity) < rule.getPercentage()) {
            return new FeatureDecision(rule.getValue(), "percentage");
        }
        return new FeatureDecision(defaultValue, DEFAULT_RULE);
    }

    private static boolean contains(java.util.List<String> values, String candidate) {
        return candidate != null && values.contains(candidate);
    }

    private static int bucket(String key, String identity) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((key + ":" + identity).getBytes(StandardCharsets.UTF_8));
            int hash = ByteBuffer.wrap(digest, 0, 4).getInt();
            return Math.floorMod(hash, 100);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
