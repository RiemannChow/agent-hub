package com.riemann.agent.presentation.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.auth.AuthenticatedAgent;
import com.riemann.agent.infrastructure.a2a.A2aTaskStoreProperties;
import com.riemann.agent.presentation.a2a.protocol.AgentCapabilities;
import com.riemann.agent.presentation.a2a.protocol.Artifact;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import com.riemann.agent.presentation.a2a.protocol.TaskStateTransition;
import com.riemann.agent.presentation.a2a.protocol.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Covers tasks/resubscribe replay and the advertised stateTransitionHistory capability. */
class A2aResubscribeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final A2aTaskStore store = new A2aTaskStore(new A2aTaskStoreProperties());
    private final A2aRpcController controller =
            new A2aRpcController(null, store, null, mapper, null);

    private MockServerWebExchange exchange(String agentId) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/a2a"));
        AuthenticatedAgent.bind(exchange, agentId);
        return exchange;
    }

    private String request() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/resubscribe\","
                + "\"params\":{\"id\":\"t1\"}}";
    }

    @Test
    void capabilitiesAdvertiseStateTransitionHistory() {
        assertThat(AgentCapabilities.defaults().stateTransitionHistory()).isTrue();
    }

    @Test
    void resubscribeReplaysTransitionsAndFinalArtifact() {
        Task task =
                new Task(
                        "t1",
                        "ctx-1",
                        TaskStatus.of(TaskState.completed),
                        List.of(Artifact.text("artifact-0", "done", 0, false, true)),
                        List.of(),
                        null,
                        List.of(
                                TaskStateTransition.of(TaskState.working, null),
                                TaskStateTransition.of(TaskState.completed, null)));
        store.put(task, "conv-1", "agent-a");

        List<String> events =
                controller
                        .handleStream(request(), exchange("agent-a"))
                        .map(ServerSentEvent::data)
                        .collectList()
                        .block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).contains("\"state\":\"working\"");
        assertThat(events.get(1)).contains("\"state\":\"completed\"").contains("\"final\":true");
        assertThat(events.get(2)).contains("\"kind\":\"artifact-update\"").contains("done");
    }

    @Test
    void resubscribeIsScopedToTaskOwner() {
        store.put(
                new Task(
                        "t1",
                        "ctx-1",
                        TaskStatus.of(TaskState.completed),
                        List.of(),
                        List.of(),
                        java.util.Map.of()),
                "conv-1",
                "agent-a");

        List<String> events =
                controller
                        .handleStream(request(), exchange("agent-b"))
                        .map(ServerSentEvent::data)
                        .collectList()
                        .block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).contains("Task not found");
    }
}
