package com.riemann.agent.presentation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounds for the in-memory SSE event bus ({@code agent.events.*}).
 *
 * <p>Before these limits the bus used {@code Sinks.many().replay().all()} and kept every
 * conversation's sink forever, so a long-running process retained the full event stream (tokens,
 * tool results, final answer) of every completed turn. See issue #21.
 */
@Component
@ConfigurationProperties(prefix = "agent.events")
public class ConversationEventBusProperties {

    /** Events retained per conversation for late SSE subscribers. */
    private int maxReplayEvents = 256;

    /** How long a sink may stay registered before it is swept. */
    private Duration ttl = Duration.ofMinutes(10);

    /** Maximum number of concurrently registered conversations. */
    private int maxActiveConversations = 1000;

    public int getMaxReplayEvents() {
        return maxReplayEvents;
    }

    public void setMaxReplayEvents(int maxReplayEvents) {
        this.maxReplayEvents = Math.max(1, maxReplayEvents);
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofMinutes(10) : ttl;
    }

    public int getMaxActiveConversations() {
        return maxActiveConversations;
    }

    public void setMaxActiveConversations(int maxActiveConversations) {
        this.maxActiveConversations = Math.max(1, maxActiveConversations);
    }
}
