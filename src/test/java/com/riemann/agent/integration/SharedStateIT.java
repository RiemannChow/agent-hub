package com.riemann.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.AgentOrchestrationService;
import com.riemann.agent.infrastructure.a2a.A2aTaskStoreProperties;
import com.riemann.agent.infrastructure.execution.ActiveTurnRegistry.ActiveTurn;
import com.riemann.agent.infrastructure.nacos.NacosAgentRegistry;
import com.riemann.agent.infrastructure.seekdb.SeekDbDataSeeder;
import com.riemann.agent.infrastructure.seekdb.books.BookDataImporter;
import com.riemann.agent.infrastructure.state.ActiveTurnStore;
import com.riemann.agent.infrastructure.state.ApprovalStore;
import com.riemann.agent.infrastructure.state.EventBusBridge;
import com.riemann.agent.infrastructure.state.NonceStore;
import com.riemann.agent.infrastructure.state.PushConfigStore;
import com.riemann.agent.infrastructure.state.QuotaState;
import com.riemann.agent.infrastructure.state.RedisActiveTurnStore;
import com.riemann.agent.infrastructure.state.RedisApprovalStore;
import com.riemann.agent.infrastructure.state.RedisCommandBus;
import com.riemann.agent.infrastructure.state.RedisEventBridge;
import com.riemann.agent.infrastructure.state.RedisJsonCodec;
import com.riemann.agent.infrastructure.state.RedisNonceStore;
import com.riemann.agent.infrastructure.state.RedisPushConfigStore;
import com.riemann.agent.infrastructure.state.RedisQuotaState;
import com.riemann.agent.infrastructure.state.RedisTaskStateStore;
import com.riemann.agent.infrastructure.state.StateBackendProperties;
import com.riemann.agent.infrastructure.state.TaskStateStore;
import com.riemann.agent.presentation.a2a.protocol.Artifact;
import com.riemann.agent.presentation.a2a.protocol.PushNotificationConfig;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import com.riemann.agent.presentation.a2a.protocol.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Boots the real Spring context against Redis/Mongo and verifies cross-replica state sharing. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("seekdb")
class SharedStateIT {

    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    private static final String KEY_PREFIX = "it-" + System.nanoTime();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.auto-index-creation", () -> false);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("agent.state.backend", () -> "redis");
        registry.add("agent.state.key-prefix", () -> KEY_PREFIX);
        registry.add("agent.quota.max-concurrent-turns-per-user", () -> 1);
        registry.add("agent.model.api-key", () -> "it-dummy-key");
        registry.add("agent.model.base-url", () -> "http://127.0.0.1:1");
        registry.add("agent.a2a.auth.shared-secret", () -> "it-shared-secret");
        registry.add("agent.a2a.client.enabled", () -> false);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("agent.probe.enabled", () -> false);
    }

    // No live model, SeekDB seed or CSV import is needed to verify shared runtime state.
    @MockBean AgentOrchestrationService orchestration;

    @MockBean SeekDbDataSeeder seeder;

    @MockBean BookDataImporter importer;

    @MockBean NacosAgentRegistry nacosRegistry;

    @Autowired ReactiveStringRedisTemplate redis;
    @Autowired RedisJsonCodec codec;
    @Autowired StateBackendProperties stateProps;
    @Autowired A2aTaskStoreProperties taskProps;
    @Autowired QuotaState quota;
    @Autowired TaskStateStore tasks;
    @Autowired PushConfigStore pushConfigs;
    @Autowired NonceStore nonces;
    @Autowired ActiveTurnStore turns;
    @Autowired ApprovalStore approvals;
    @Autowired EventBusBridge eventBridge;
    @LocalServerPort int port;

    private RedisQuotaState secondQuota() {
        return new RedisQuotaState(redis, codec, stateProps);
    }

    private RedisTaskStateStore secondTasks() {
        return new RedisTaskStateStore(redis, codec, stateProps, taskProps);
    }

    private RedisPushConfigStore secondPush() {
        return new RedisPushConfigStore(redis, codec, stateProps);
    }

    private RedisNonceStore secondNonce() {
        return new RedisNonceStore(redis, codec, stateProps);
    }

    private RedisActiveTurnStore secondTurns() {
        return new RedisActiveTurnStore(redis, codec, stateProps);
    }

    private RedisApprovalStore secondApprovals() {
        return new RedisApprovalStore(redis, codec, stateProps);
    }

    @Test
    void redisImplementationsAreSelected() {
        assertThat(quota).isInstanceOf(RedisQuotaState.class);
        assertThat(tasks).isInstanceOf(RedisTaskStateStore.class);
        assertThat(pushConfigs).isInstanceOf(RedisPushConfigStore.class);
        assertThat(nonces).isInstanceOf(RedisNonceStore.class);
        assertThat(turns).isInstanceOf(RedisActiveTurnStore.class);
        assertThat(approvals).isInstanceOf(RedisApprovalStore.class);
        assertThat(eventBridge).isInstanceOf(RedisEventBridge.class);
    }

    @Test
    void quotaSlotsAndDailyTokensAreShared() {
        String user = "it-user-quota";
        assertThat(quota.acquire(user, 0, 1)).isEqualTo(0);
        // A second replica sees the occupied slot and must refuse the same user.
        assertThat(secondQuota().acquire(user, 0, 1)).isEqualTo(2);
        quota.release(user);

        assertThat(secondQuota().acquire(user, 0, 1)).isEqualTo(0);
        quota.release(user);
        secondQuota().recordTokens(user, 12, 10);
        assertThat(quota.tokensUsedToday(user)).isEqualTo(12L);
        assertThat(quota.acquire(user, 10, 1)).isEqualTo(1);
    }

    @Test
    void a2aTasksPushConfigsNoncesAndTurnsAreShared() {
        String owner = "it-agent";
        String taskId = "it-task-1";
        String conversationId = "it-conv-1";
        Task task =
                new Task(
                        taskId,
                        "ctx-1",
                        TaskStatus.of(TaskState.completed),
                        List.of(Artifact.text("a0", "ok", 0, false, true)),
                        List.of(),
                        Map.of());
        tasks.put(task, conversationId, owner);

        assertThat(secondTasks().get(taskId, owner)).contains(task);
        assertThat(secondTasks().conversationIdOf(taskId, owner)).contains(conversationId);
        assertThat(secondTasks().list(owner, null, null, 10, 0)).hasSize(1);
        assertThat(secondTasks().get(taskId, "other-agent")).isEmpty();

        PushNotificationConfig config =
                new PushNotificationConfig("default", "http://example.com/hook", null);
        pushConfigs.set(taskId, config, 5);
        assertThat(secondPush().get(taskId, "default")).contains(config);

        assertThat(nonces.register("sig-1", 60)).isTrue();
        assertThat(secondNonce().register("sig-1", 60)).isFalse();

        ActiveTurn turn = new ActiveTurn(conversationId, "user-1", "session-1", Instant.now());
        turns.register(turn);
        assertThat(secondTurns().find(conversationId))
                .hasValueSatisfying(
                        found -> {
                            assertThat(found.conversationId()).isEqualTo(conversationId);
                            assertThat(found.userId()).isEqualTo("user-1");
                            assertThat(found.sessionId()).isEqualTo("session-1");
                        });
        turns.unregister(turn);
        assertThat(secondTurns().find(conversationId)).isEmpty();
    }

    @Test
    void approvalsCanBeClaimedFromAnotherReplica() {
        ApprovalStore.ApprovalMetadata metadata =
                new ApprovalStore.ApprovalMetadata(
                        "it-approval",
                        "user-1",
                        "conv-1",
                        "seek_commit_sandbox",
                        "commit sandbox",
                        Instant.now().plusSeconds(60));
        approvals.save(metadata);

        assertThat(secondApprovals().view("it-approval")).contains(metadata);
        assertThat(secondApprovals().claim("it-approval", "wrong-user").status())
                .isEqualTo(ApprovalStore.ClaimStatus.FORBIDDEN);
        assertThat(approvals.claim("it-approval", "user-1").status())
                .isEqualTo(ApprovalStore.ClaimStatus.CLAIMED);
        assertThat(secondApprovals().view("it-approval")).isEmpty();
    }

    @Test
    void sseEventsReachLateSubscribersOnAnotherReplica() {
        Sinks.Many<AgentEvent> origin = Sinks.many().replay().limit(16);
        Sinks.Many<AgentEvent> replay = Sinks.many().replay().limit(16);
        EventBusBridge.Handle originHandle = eventBridge.attach("it-sse-conv", origin, true);
        EventBusBridge.Handle replayHandle = eventBridge.attach("it-sse-conv", replay, false);

        try {
            StepVerifier.create(replay.asFlux())
                    .then(() -> origin.tryEmitNext(new AgentEvent.Token("cross-pod")))
                    .expectNextMatches(
                            event ->
                                    event instanceof AgentEvent.Token t
                                            && "cross-pod".equals(t.delta()))
                    .thenCancel()
                    .verify(Duration.ofSeconds(10));
        } finally {
            originHandle.dispose();
            replayHandle.dispose();
        }
    }

    @Test
    void commandsAreRoutedToTheOwningReplica() {
        // Isolated channel namespace: the application context's own approval listener must not
        // answer this test command.
        StateBackendProperties isolated = new StateBackendProperties();
        isolated.setBackend(StateBackendProperties.Backend.REDIS);
        isolated.setKeyPrefix(KEY_PREFIX + "-cmd");
        RedisCommandBus sender = new RedisCommandBus(redis, codec, isolated);
        RedisCommandBus owner = new RedisCommandBus(redis, codec, isolated);
        sender.subscribe();
        owner.subscribe();
        // Reactor Redis subscriptions are established asynchronously; wait for the channels.
        reactor.core.publisher.Mono.delay(Duration.ofSeconds(1)).block();
        owner.onApprovalCommand(
                (id, user) -> sender.publishApprovalResult(id, "executed-by-" + user, false));

        StepVerifier.create(
                        sender.executeApprovalOnOwner("it-cmd-1", "user-1", Duration.ofSeconds(10)))
                .expectNext("executed-by-user-1")
                .verifyComplete();
    }

    @Test
    void publicAgentCardIsServed() {
        WebTestClient client =
                WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        client.get()
                .uri("/.well-known/agent.json")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.capabilities.stateTransitionHistory")
                .isEqualTo(true);
    }
}
