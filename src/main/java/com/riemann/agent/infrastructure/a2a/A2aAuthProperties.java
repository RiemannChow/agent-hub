package com.riemann.agent.infrastructure.a2a;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Service-to-service authentication for the A2A JSON-RPC endpoint ({@code agent.a2a.auth.*}).
 *
 * <p>Before these properties existed, {@code POST /a2a} trusted a non-blank {@code X-Agent-Id}
 * header as identity, so anyone could drive the agent. All peers sharing this secret form one trust
 * domain; a request must carry an HMAC-SHA256 signature over its method, path, timestamp, agent id
 * and body (see {@link A2aSignature}).
 *
 * <p><b>Fail closed:</b> an empty {@code sharedSecret} rejects every {@code /a2a} request. There is
 * deliberately no insecure default — set {@code AGENT_A2A_SHARED_SECRET} on every peer to enable
 * A2A. {@code /.well-known/agent.json} stays public per the A2A spec.
 */
@Component
@ConfigurationProperties(prefix = "agent.a2a.auth")
public class A2aAuthProperties {

    /** Shared secret used for HMAC-SHA256. Empty disables the whole A2A endpoint. */
    private String sharedSecret = "";

    /** Stable identity of this agent; also used as the task/quota owner for outbound calls. */
    private String agentId = "agent-hub";

    /** Maximum accepted clock difference between peers. */
    private Duration allowedSkew = Duration.ofMinutes(5);

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
    }

    public boolean isConfigured() {
        return !sharedSecret.isEmpty();
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = (agentId == null || agentId.isBlank()) ? "agent-hub" : agentId.trim();
    }

    public Duration getAllowedSkew() {
        return allowedSkew;
    }

    public void setAllowedSkew(Duration allowedSkew) {
        this.allowedSkew =
                (allowedSkew == null || allowedSkew.isNegative() || allowedSkew.isZero())
                        ? Duration.ofMinutes(5)
                        : allowedSkew;
    }
}
