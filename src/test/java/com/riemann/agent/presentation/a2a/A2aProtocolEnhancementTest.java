package com.riemann.agent.presentation.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.infrastructure.a2a.A2aTaskStoreProperties;
import com.riemann.agent.presentation.a2a.protocol.AgentCapabilities;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import com.riemann.agent.presentation.a2a.protocol.TaskStatus;
import com.riemann.agent.presentation.a2a.protocol.TaskStatusUpdateEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression tests for tasks/list, input-required state and protocol event fields. */
class A2aProtocolEnhancementTest {

    private static A2aTaskStore store() {
        return new A2aTaskStore(new A2aTaskStoreProperties());
    }

    private static Task task(String id, String contextId, TaskState state) {
        return new Task(id, contextId, TaskStatus.of(state), List.of(), List.of(), Map.of());
    }

    @Test
    void listIsScopedToTheVerifiedOwner() {
        A2aTaskStore store = store();
        store.put(task("t1", "ctx-1", TaskState.completed), "conv-1", "agent-a");
        store.put(task("t2", "ctx-1", TaskState.input_required), "conv-2", "agent-a");
        store.put(task("t3", "ctx-2", TaskState.completed), "conv-3", "agent-b");

        assertThat(store.list("agent-a", null, null, 50, 0)).hasSize(2);
        assertThat(store.list("agent-b", null, null, 50, 0))
                .extracting(Task::id)
                .containsExactly("t3");
        assertThat(store.count("agent-a", null, null)).isEqualTo(2);
    }

    @Test
    void listSupportsContextStateFilterAndPaging() {
        A2aTaskStore store = store();
        store.put(task("t1", "ctx-1", TaskState.completed), "conv-1", "agent-a");
        store.put(task("t2", "ctx-1", TaskState.input_required), "conv-2", "agent-a");
        store.put(task("t3", "ctx-2", TaskState.completed), "conv-3", "agent-a");

        assertThat(store.list("agent-a", "ctx-1", TaskState.input_required, 50, 0))
                .extracting(Task::id)
                .containsExactly("t2");
        assertThat(store.list("agent-a", null, null, 1, 0)).hasSize(1);
        assertThat(store.list("agent-a", null, null, 1, 1)).hasSize(1);
        assertThat(store.list("agent-a", null, null, 1, 3)).isEmpty();
    }

    @Test
    void inputMarkerIsStrippedAndBecomesTheQuestion() {
        A2aRpcController.Answer answer =
                A2aRpcController.parseAnswer("缺少时间范围。\n[[A2A:INPUT_REQUIRED]]请告诉我要查哪个月份？");

        assertThat(answer.text()).isEqualTo("缺少时间范围。");
        assertThat(answer.question()).isEqualTo("请告诉我要查哪个月份？");
    }

    @Test
    void answerWithoutMarkerStaysCompleted() {
        A2aRpcController.Answer answer = A2aRpcController.parseAnswer("本月 GMV 是 12000。");

        assertThat(answer.text()).isEqualTo("本月 GMV 是 12000。");
        assertThat(answer.question()).isNull();
    }

    @Test
    void emptyQuestionGetsAFallback() {
        A2aRpcController.Answer answer = A2aRpcController.parseAnswer("[[A2A:INPUT_REQUIRED]]   ");

        assertThat(answer.text()).isEmpty();
        assertThat(answer.question()).isEqualTo("请补充必要信息后继续。");
    }

    @Test
    void capabilitiesAdvertisePushNotifications() {
        assertThat(AgentCapabilities.defaults().pushNotifications()).isTrue();
        assertThat(AgentCapabilities.defaults().streaming()).isTrue();
    }

    @Test
    void streamEventsCarryKindAndFinalFields() throws Exception {
        TaskStatusUpdateEvent event =
                TaskStatusUpdateEvent.last("ctx-1", "t1", TaskStatus.of(TaskState.completed));

        assertThat(event.kind()).isEqualTo("status-update");
        assertThat(event.isFinal()).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(event)).contains("\"final\":true");
    }
}
