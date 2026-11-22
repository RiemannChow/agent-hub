package com.riemann.agent.presentation.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.infrastructure.a2a.A2aEndpointGuard;
import com.riemann.agent.infrastructure.a2a.A2aPushProperties;
import com.riemann.agent.infrastructure.state.LocalPushConfigStore;
import com.riemann.agent.infrastructure.state.PushConfigStore;
import com.riemann.agent.presentation.a2a.protocol.PushNotificationConfig;
import com.riemann.agent.presentation.a2a.protocol.Task;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A2A 推送通知：任务状态变化时向对端注册的 URL POST 一条 Task 快照。
 *
 * <p>配置按 taskId 隔离，且写入/读取/删除都先经 {@link A2aTaskStore#get} 校验 owner —— 只有任务的 创建者才能注册或读取它的通知配置。URL 经过
 * {@link A2aEndpointGuard} 校验，防止被投毒的服务发现 数据变成 SSRF 跳板。通知带时间戳 + HMAC-SHA256
 * 签名，对端可验签。投递失败只记日志，不影响主流程。
 */
@Component
public class A2aPushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(A2aPushNotificationService.class);
    private static final String TIMESTAMP_HEADER = "X-A2A-Notification-Timestamp";
    private static final String SIGNATURE_HEADER = "X-A2A-Notification-Signature";

    private final A2aTaskStore taskStore;
    private final A2aPushProperties props;
    private final A2aEndpointGuard endpointGuard;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final PushConfigStore configs;

    public A2aPushNotificationService(
            A2aTaskStore taskStore,
            A2aPushProperties props,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        this(taskStore, props, objectMapper, webClientBuilder, new LocalPushConfigStore());
    }

    @Autowired
    public A2aPushNotificationService(
            A2aTaskStore taskStore,
            A2aPushProperties props,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder,
            PushConfigStore configs) {
        this.taskStore = taskStore;
        this.props = props;
        this.objectMapper = objectMapper;
        this.endpointGuard = new A2aEndpointGuard(props.getAllowedHosts());
        this.webClient = webClientBuilder.build();
        this.configs = configs;
    }

    /** 注册/替换通知配置。任务不属于该调用方时抛 IllegalArgumentException。 */
    public void set(String owner, String taskId, PushNotificationConfig config) {
        requireOwned(owner, taskId);
        if (config == null || config.url() == null || config.url().isBlank()) {
            throw new IllegalArgumentException("pushNotificationConfig.url is required");
        }
        URI uri = endpointGuard.validate(config.url());
        configs.set(
                taskId,
                new PushNotificationConfig(config.id(), uri.toString(), config.token()),
                props.getMaxConfigsPerTask());
    }

    public Optional<PushNotificationConfig> get(String owner, String taskId, String configId) {
        requireOwned(owner, taskId);
        return configs.get(taskId, configId);
    }

    public List<PushNotificationConfig> list(String owner, String taskId) {
        requireOwned(owner, taskId);
        return configs.list(taskId);
    }

    /**
     * @return true 表示确实删除了一条配置
     */
    public boolean delete(String owner, String taskId, String configId) {
        requireOwned(owner, taskId);
        return configs.delete(taskId, configId);
    }

    /** 任务状态变化时调用；对所有已注册配置并发投递，失败不影响调用方。 */
    public void notify(Task task) {
        if (!props.isEnabled() || task == null) {
            return;
        }
        List<PushNotificationConfig> forTask = configs.list(task.id());
        if (forTask.isEmpty()) {
            return;
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("task", task));
        } catch (Exception e) {
            log.warn("[A2A] Failed to serialize push notification for task {}", task.id(), e);
            return;
        }
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        for (PushNotificationConfig config : forTask) {
            String secret =
                    config.token() != null && !config.token().isBlank()
                            ? config.token()
                            : props.getSignatureSecret();
            String signature = sign(secret, timestamp, body);
            webClient
                    .post()
                    .uri(config.url())
                    .header("Content-Type", "application/json")
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(SIGNATURE_HEADER, signature)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(10))
                    .onErrorResume(
                            e -> {
                                log.warn(
                                        "[A2A] Push notification to {} failed for task {}: {}",
                                        config.url(),
                                        task.id(),
                                        e.getMessage());
                                return reactor.core.publisher.Mono.empty();
                            })
                    .subscribe();
        }
    }

    private void requireOwned(String owner, String taskId) {
        if (taskId == null || taskStore.get(taskId, owner).isEmpty()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
    }

    private String sign(String secret, String timestamp, String body) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
