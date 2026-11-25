package com.riemann.agent.infrastructure.toolkit.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the tool registry slice. */
@Component
@ConfigurationProperties(prefix = "agent.tools")
public class ToolRegistryProperties {

    /** Seed descriptors for the tools that ship with agent-hub. */
    private boolean seedBuiltIns = true;

    /** Maximum audit entries retained per run in the in-memory store. */
    private int auditCapacityPerRun = 256;

    public boolean isSeedBuiltIns() {
        return seedBuiltIns;
    }

    public void setSeedBuiltIns(boolean seedBuiltIns) {
        this.seedBuiltIns = seedBuiltIns;
    }

    public int getAuditCapacityPerRun() {
        return auditCapacityPerRun;
    }

    public void setAuditCapacityPerRun(int auditCapacityPerRun) {
        if (auditCapacityPerRun <= 0) {
            throw new IllegalArgumentException(
                    "agent.tools.audit-capacity-per-run must be greater than 0");
        }
        this.auditCapacityPerRun = auditCapacityPerRun;
    }
}
