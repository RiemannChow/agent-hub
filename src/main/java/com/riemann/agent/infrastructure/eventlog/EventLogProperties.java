package com.riemann.agent.infrastructure.eventlog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the in-memory event log slice. */
@Component
@ConfigurationProperties(prefix = "agent.eventlog")
public class EventLogProperties {

    /** Maximum number of events retained per run. */
    private int capacityPerRun = 512;

    public int getCapacityPerRun() {
        return capacityPerRun;
    }

    public void setCapacityPerRun(int capacityPerRun) {
        if (capacityPerRun <= 0) {
            throw new IllegalArgumentException("agent.eventlog.capacity-per-run must be positive");
        }
        this.capacityPerRun = capacityPerRun;
    }
}
