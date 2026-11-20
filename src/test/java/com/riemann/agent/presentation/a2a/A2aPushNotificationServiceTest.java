package com.riemann.agent.presentation.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.infrastructure.a2a.A2aPushProperties;
import com.riemann.agent.presentation.a2a.protocol.PushNotificationConfig;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import com.riemann.agent.presentation.a2a.protocol.TaskStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

@ExtendWith(MockitoExtension.class)
class A2aPushNotificationServiceTest {

    @Mock private A2aTaskStore taskStore;

    private A2aPushNotificationService service;
    private Task ownedTask;

    @BeforeEach
    void setUp() {
        A2aPushProperties props = new A2aPushProperties();
        service =
                new A2aPushNotificationService(
                        taskStore, props, new ObjectMapper(), WebClient.builder());
        ownedTask =
                new Task(
                        "t1",
                        "ctx-1",
                        TaskStatus.of(TaskState.completed),
                        List.of(),
                        List.of(),
                        Map.of());
        lenient().when(taskStore.get("t1", "agent-a")).thenReturn(Optional.of(ownedTask));
    }

    @Test
    void foreignPeerCannotRegisterPushConfig() {
        assertThatThrownBy(
                        () ->
                                service.set(
                                        "agent-b",
                                        "t1",
                                        new PushNotificationConfig(
                                                "d", "https://example.com/h", "s")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task not found");
    }

    @Test
    void unsafeUrlIsRejected() {
        assertThatThrownBy(
                        () ->
                                service.set(
                                        "agent-a",
                                        "t1",
                                        new PushNotificationConfig(
                                                "d", "ftp://example.com/h", "s")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
    }

    @Test
    void ownerCanRegisterReadAndDeleteConfig() {
        service.set("agent-a", "t1", new PushNotificationConfig("d", "https://example.com/h", "s"));

        assertThat(service.get("agent-a", "t1", "d")).isPresent();
        assertThat(service.list("agent-a", "t1")).hasSize(1);
        assertThat(service.delete("agent-a", "t1", "d")).isTrue();
        assertThat(service.get("agent-a", "t1", "d")).isEmpty();
    }

    @Test
    void foreignPeerCannotReadOrDeleteConfig() {
        service.set("agent-a", "t1", new PushNotificationConfig("d", "https://example.com/h", "s"));

        assertThatThrownBy(() -> service.get("agent-b", "t1", "d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete("agent-b", "t1", "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
