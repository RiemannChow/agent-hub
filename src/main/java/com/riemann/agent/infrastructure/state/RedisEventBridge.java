package com.riemann.agent.infrastructure.state;

import com.riemann.agent.domain.AgentEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Redis pub/sub fan-out for a conversation's SSE events and completion signal. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisEventBridge extends RedisSupport implements EventBusBridge {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBridge.class);

    public RedisEventBridge(
            ReactiveStringRedisTemplate redis, RedisJsonCodec codec, StateBackendProperties props) {
        super(redis, codec, props);
    }

    private record EventMessage(String origin, AgentEvent event, boolean complete) {

        static EventMessage of(String origin, AgentEvent event) {
            return new EventMessage(origin, event, false);
        }

        static EventMessage done(String origin) {
            return new EventMessage(origin, null, true);
        }
    }

    @Override
    public Handle attach(String conversationId, Sinks.Many<AgentEvent> sink, boolean publishLocal) {
        String channel = channel(conversationId);
        String origin = UUID.randomUUID().toString();

        Disposable outbound =
                publishLocal
                        ? sink.asFlux()
                                .doOnNext(
                                        event ->
                                                send(
                                                        channel,
                                                        codec.write(
                                                                EventMessage.of(origin, event))))
                                .doFinally(
                                        signal ->
                                                send(
                                                        channel,
                                                        codec.write(EventMessage.done(origin))))
                                .subscribe()
                        : Disposables.disposed();

        Disposable inbound =
                redis.listenTo(ChannelTopic.of(channel))
                        .map(msg -> codec.read(msg.getMessage(), EventMessage.class))
                        .filter(message -> !origin.equals(message.origin()))
                        .subscribe(
                                message -> {
                                    if (message.complete()) {
                                        sink.tryEmitComplete();
                                    } else if (message.event() != null) {
                                        sink.tryEmitNext(message.event());
                                    }
                                },
                                e ->
                                        log.warn(
                                                "[state] SSE bridge failed for {}",
                                                conversationId,
                                                e));

        return new Handle() {
            @Override
            public void dispose() {
                outbound.dispose();
                inbound.dispose();
            }

            @Override
            public boolean isDisposed() {
                return inbound.isDisposed();
            }
        };
    }

    private void send(String channel, String payload) {
        redis.convertAndSend(channel, payload)
                .doOnError(e -> log.warn("[state] Failed to publish SSE event to {}", channel, e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private String channel(String conversationId) {
        return key("sse", conversationId);
    }
}
