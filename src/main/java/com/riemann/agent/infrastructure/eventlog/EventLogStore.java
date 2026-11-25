package com.riemann.agent.infrastructure.eventlog;

import java.util.List;

/** Append-only event log abstraction for run replay and debugging. */
public interface EventLogStore {

    EventEnvelope append(EventEnvelope event);

    List<EventEnvelope> query(String runId);

    List<EventEnvelope> query(String runId, long fromSequence);
}
