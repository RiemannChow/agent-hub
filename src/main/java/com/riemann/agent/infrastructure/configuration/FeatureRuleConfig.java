package com.riemann.agent.infrastructure.configuration;

import java.util.List;

/** Bindable feature rule; {@code percentage} is 0-100. */
public class FeatureRuleConfig {

    private String value = "false";
    private List<String> userAllowlist = List.of();
    private List<String> conversationAllowlist = List.of();
    private int percentage = 0;
    private String environment = "";

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public List<String> getUserAllowlist() {
        return userAllowlist;
    }

    public void setUserAllowlist(List<String> userAllowlist) {
        this.userAllowlist = userAllowlist == null ? List.of() : List.copyOf(userAllowlist);
    }

    public List<String> getConversationAllowlist() {
        return conversationAllowlist;
    }

    public void setConversationAllowlist(List<String> conversationAllowlist) {
        this.conversationAllowlist =
                conversationAllowlist == null ? List.of() : List.copyOf(conversationAllowlist);
    }

    public int getPercentage() {
        return percentage;
    }

    public void setPercentage(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("feature percentage must be between 0 and 100");
        }
        this.percentage = percentage;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment == null ? "" : environment;
    }
}
