package com.riemann.agent.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riemann.agent.application.ConversationAppService;
import com.riemann.agent.application.ModelHealthProbe;
import com.riemann.agent.auth.AuthenticatedUser;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.ConversationId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** 探针接入回归测试：精确问候必须走探针分支（不建会话、不扣配额）； 带任务的问候必须照常进入 Agent 编排，不能被心跳逻辑吞掉。 */
@ExtendWith(MockitoExtension.class)
class AgentChatControllerProbeTest {

    @Mock private ConversationAppService appService;

    private AgentChatController controller;
    private ModelHealthProbe realProbe;

    @BeforeEach
    void setUp() {
        ConversationEventBus eventBus = mock(ConversationEventBus.class);
        when(eventBus.register(any(ConversationId.class)))
                .thenAnswer(inv -> Sinks.many().replay().limit(32));
        realProbe =
                new ModelHealthProbe(
                        mock(io.agentscope.core.model.Model.class),
                        true,
                        Duration.ofSeconds(1),
                        "服务正常，智能体已就绪，请继续提问吧。",
                        "模型服务暂时不可用。");
        controller = new AgentChatController(appService, eventBus, null, realProbe);
    }

    private ServerWebExchange exchange() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/agent/chat"));
        AuthenticatedUser.bind(exchange, "user-a");
        return exchange;
    }

    @Test
    void greetingGoesToProbeNotOrchestration() {
        AgentChatController.ChatRequest req =
                new AgentChatController.ChatRequest("user-a", "你好", null, List.of());

        StepVerifier.create(controller.chat(req, exchange()).map(e -> e.data()))
                .expectNextCount(2) // Token(可用文案) + Completed
                .verifyComplete();

        verify(appService, never())
                .chat(anyString(), anyString(), any(ConversationId.class), any(Map.class));
    }

    @Test
    void greetingWithTaskStillGoesToOrchestration() {
        when(appService.chat(anyString(), anyString(), any(ConversationId.class), any(Map.class)))
                .thenReturn(
                        Flux.just(
                                new AgentEvent.Token("这是正常回答"),
                                new AgentEvent.Completed("whatever")));

        AgentChatController.ChatRequest req =
                new AgentChatController.ChatRequest("user-a", "你好，帮我查下 GMV", "c-9", List.of());

        StepVerifier.create(controller.chat(req, exchange()).map(e -> e.data()))
                .expectNextCount(2)
                .verifyComplete();

        verify(appService)
                .chat(anyString(), anyString(), any(ConversationId.class), any(Map.class));
    }
}
