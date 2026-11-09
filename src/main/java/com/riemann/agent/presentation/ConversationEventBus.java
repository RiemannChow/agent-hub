package com.riemann.agent.presentation;

import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.ConversationId;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Keeps one bounded SSE sink per conversation: the POST /chat endpoint writes events, the GET
 * /stream endpoint (or a late subscriber) reads the same stream.
 *
 * <p><b>Why replay instead of multicast:</b> a replay sink buffers events already emitted, so a
 * subscriber that arrives a tick or two late still receives the current turn. A plain multicast
 * sink drops events while it has zero subscribers. The buffer is bounded by {@link
 * ConversationEventBusProperties#getMaxReplayEvents()} — the old {@code replay().all()} kept every
 * token, tool result and the final answer for the whole lifetime of the process.
 *
 * <p><b>Why a brand new sink every turn:</b> reusing one sink via {@code computeIfAbsent} meant the
 * next turn on the same conversationId got an already-completed sink, which immediately replayed
 * the first turn's answer and completed. That was the "every follow-up answer equals the first
 * answer" bug. {@link #register} therefore always creates a fresh sink and completes the previous
 * one.
 *
 * <p><b>Leak fix (issue #21):</b> the map entry used to be removed only on client CANCEL. A
 * normally completed or failed turn left its sink (and its whole replay buffer) in the map forever.
 * {@link AgentChatController} now releases the sink on every termination signal; this class
 * additionally adds a TTL sweep and a hard capacity cap so that even a stalled upstream that never
 * terminates cannot grow the heap without bound.
 *
 * <p><b>Still single-instance only:</b> sinks live in this JVM's heap. Multi-instance deployments
 * need Redis / a message broker for SSE, cancellation and quota state; that is tracked separately
 * and is not solved by these bounds.
 */
@Component
public class ConversationEventBus {

    private static final Logger log = LoggerFactory.getLogger(ConversationEventBus.class);

    private final Map<String, Entry> sinks = new ConcurrentHashMap<>();
    private final ConversationEventBusProperties props;

    public ConversationEventBus(ConversationEventBusProperties props) {
        this.props = props;
    }

    /** A sink plus the timestamps used for TTL and oldest-first eviction. */
    private record Entry(Sinks.Many<AgentEvent> sink, Instant createdAt, Instant expiresAt) {

        boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    /** Registers a fresh sink for this turn and returns it. */
    public Sinks.Many<AgentEvent> register(ConversationId id) {
        Instant now = Instant.now();
        Duration ttl = props.getTtl();
        Sinks.Many<AgentEvent> sink = Sinks.many().replay().limit(props.getMaxReplayEvents());
        Entry previous = sinks.put(id.value(), new Entry(sink, now, now.plus(ttl)));
        if (previous != null) {
            previous.sink().tryEmitComplete();
        }
        sweep(now);
        evictIfNeeded();
        return sink;
    }

    /** Returns the current turn's stream, or an error if it is missing / expired. */
    public Flux<AgentEvent> flux(ConversationId id) {
        Entry entry = sinks.get(id.value());
        if (entry == null) {
            return Flux.error(new IllegalStateException("no active stream for " + id.value()));
        }
        if (entry.expired(Instant.now())) {
            if (sinks.remove(id.value(), entry)) {
                entry.sink().tryEmitComplete();
            }
            return Flux.error(new IllegalStateException("no active stream for " + id.value()));
        }
        return entry.sink().asFlux();
    }

    /**
     * Releases a sink after the stream terminates. Only removes the entry when it still holds
     * exactly this sink, so a newer turn on the same conversationId is never deleted by mistake.
     */
    public void remove(ConversationId id, Sinks.Many<AgentEvent> sink) {
        if (sink == null) {
            return;
        }
        Entry entry = sinks.get(id.value());
        if (entry != null && entry.sink() == sink && sinks.remove(id.value(), entry)) {
            sink.tryEmitComplete();
        }
    }

    /** Number of currently registered sinks (also useful for tests and metrics). */
    public int size() {
        return sinks.size();
    }

    /** Periodically drops expired entries; runs off the request threads. */
    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        sweep(Instant.now());
    }

    /** Visible for tests: sweep with an explicit clock instant. */
    void sweep(Instant now) {
        int before = sinks.size();
        sinks.entrySet()
                .removeIf(
                        e -> {
                            if (!e.getValue().expired(now)) {
                                return false;
                            }
                            e.getValue().sink().tryEmitComplete();
                            return true;
                        });
        int removed = before - sinks.size();
        if (removed > 0) {
            log.debug("[SSE] Evicted {} expired sinks, {} remaining", removed, sinks.size());
        }
    }

    /** Hard cap on live conversations; evicts oldest-first and completes their sinks. */
    private void evictIfNeeded() {
        int max = props.getMaxActiveConversations();
        if (sinks.size() <= max) {
            return;
        }
        List<Map.Entry<String, Entry>> ordered =
                sinks.entrySet().stream()
                        .sorted(Comparator.comparing(e -> e.getValue().createdAt()))
                        .toList();
        int toRemove = sinks.size() - max;
        for (int i = 0; i < toRemove && i < ordered.size(); i++) {
            Map.Entry<String, Entry> oldest = ordered.get(i);
            if (sinks.remove(oldest.getKey(), oldest.getValue())) {
                oldest.getValue().sink().tryEmitComplete();
            }
        }
        log.debug("[SSE] Active streams exceeded cap {}, evicted {} oldest", max, toRemove);
    }
}
