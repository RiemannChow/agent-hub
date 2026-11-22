package com.riemann.agent.infrastructure.state;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Redis pub/sub command router for approvals and cross-pod turn cancellation. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisCommandBus extends RedisSupport implements CommandBus {

    private static final Logger log = LoggerFactory.getLogger(RedisCommandBus.class);

    private final Map<String, Sinks.One<Result>> waiting = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, String> approvalHandler = (id, user) -> {};
    private volatile Consumer<String> cancelHandler = id -> {};

    public RedisCommandBus(
            ReactiveStringRedisTemplate redis, RedisJsonCodec codec, StateBackendProperties props) {
        super(redis, codec, props);
    }

    private record ApprovalCommand(String approvalId, String userId) {}

    private record ResultEnvelope(String approvalId, Result result) {}

    @PostConstruct
    public void subscribe() {
        redis.listenTo(ChannelTopic.of(approvalChannel()))
                .map(msg -> codec.read(msg.getMessage(), ApprovalCommand.class))
                .subscribe(
                        cmd -> {
                            try {
                                approvalHandler.accept(cmd.approvalId(), cmd.userId());
                            } catch (Exception e) {
                                log.warn(
                                        "[state] Approval command failed for {}",
                                        cmd.approvalId(),
                                        e);
                                publishApprovalResult(cmd.approvalId(), e.toString(), true);
                            }
                        },
                        e -> log.error("[state] Approval command subscription failed", e));

        redis.listenTo(ChannelTopic.of(cancelChannel()))
                .map(msg -> msg.getMessage())
                .subscribe(
                        conversationId -> {
                            try {
                                cancelHandler.accept(conversationId);
                            } catch (Exception e) {
                                log.warn("[state] Cancel command failed for {}", conversationId, e);
                            }
                        },
                        e -> log.error("[state] Cancel command subscription failed", e));

        redis.listenTo(org.springframework.data.redis.listener.PatternTopic.of(resultPattern()))
                .map(msg -> codec.read(msg.getMessage(), ResultEnvelope.class))
                .subscribe(
                        envelope -> {
                            Sinks.One<Result> sink = waiting.get(envelope.approvalId());
                            if (sink != null) {
                                sink.tryEmitValue(envelope.result());
                            }
                        },
                        e -> log.error("[state] Approval result subscription failed", e));
    }

    @Override
    public void onApprovalCommand(BiConsumer<String, String> handler) {
        this.approvalHandler = handler;
    }

    @Override
    public void onCancelCommand(Consumer<String> handler) {
        this.cancelHandler = handler;
    }

    @Override
    public Mono<String> executeApprovalOnOwner(String approvalId, String userId, Duration timeout) {
        Sinks.One<Result> sink = Sinks.one();
        waiting.put(approvalId, sink);
        send(approvalChannel(), codec.write(new ApprovalCommand(approvalId, userId)));
        return sink.asMono()
                .timeout(timeout)
                .flatMap(
                        result ->
                                result.failed()
                                        ? Mono.error(new IllegalStateException(result.value()))
                                        : Mono.just(result.value()))
                .doFinally(signal -> waiting.remove(approvalId));
    }

    @Override
    public void publishApprovalResult(String approvalId, String result, boolean failed) {
        send(
                resultChannel(approvalId),
                codec.write(new ResultEnvelope(approvalId, new Result(result, failed))));
    }

    @Override
    public void publishCancel(String conversationId) {
        send(cancelChannel(), conversationId);
    }

    private void send(String channel, String payload) {
        redis.convertAndSend(channel, payload)
                .doOnError(e -> log.warn("[state] Failed to publish to {}", channel, e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private String approvalChannel() {
        return key("cmd", "approval");
    }

    private String cancelChannel() {
        return key("cmd", "cancel");
    }

    private String resultChannel(String approvalId) {
        return key("cmd", "approval", "result", approvalId);
    }

    private String resultPattern() {
        return key("cmd", "approval", "result", "*");
    }
}
