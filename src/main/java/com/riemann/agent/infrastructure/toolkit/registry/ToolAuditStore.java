package com.riemann.agent.infrastructure.toolkit.registry;

import java.util.List;

/** Persistence abstraction for tool invocation audit records. */
public interface ToolAuditStore {

    void record(ToolInvocationAudit entry);

    List<ToolInvocationAudit> queryByRun(String runId);
}
