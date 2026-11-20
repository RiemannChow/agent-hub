package com.riemann.agent.infrastructure.a2a;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** A2A 推送通知配置（{@code agent.a2a.push.*}）。 */
@Component
@ConfigurationProperties(prefix = "agent.a2a.push")
public class A2aPushProperties {

    private boolean enabled = true;
    private List<String> allowedHosts = List.of();
    private String signatureSecret = "";
    private int maxConfigsPerTask = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public String getSignatureSecret() {
        return signatureSecret;
    }

    public void setSignatureSecret(String signatureSecret) {
        this.signatureSecret = signatureSecret;
    }

    public int getMaxConfigsPerTask() {
        return maxConfigsPerTask;
    }

    public void setMaxConfigsPerTask(int maxConfigsPerTask) {
        this.maxConfigsPerTask = maxConfigsPerTask;
    }
}
