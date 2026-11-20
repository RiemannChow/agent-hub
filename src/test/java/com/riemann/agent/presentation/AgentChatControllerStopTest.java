package com.riemann.agent.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riemann.agent.application.ConversationAppService;
import com.riemann.agent.auth.AuthenticatedUser;
import com.riemann.agent.domain.ConversationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 回归测试：/stop 之前只校验登录，任何用户都能中断别人的会话（issue #27）。 */
@ExtendWith(MockitoExtension.class)
class AgentChatControllerStopTest {

    @Mock private ConversationAppService appService;
    @Mock private ConversationEventBus eventBus;

    private AgentChatController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentChatController(appService, eventBus);
    }

    private MockServerWebExchange exchange(String user) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/agent/chat/c-1/stop"));
        AuthenticatedUser.bind(exchange, user);
        return exchange;
    }

    @Test
    void foreignConversationCannotBeStopped() {
        when(appService.getConversationForUser(any(ConversationId.class), eq("user-a")))
                .thenReturn(Mono.empty());

        StepVerifier.create(controller.stop("c-1", exchange("user-a")))
                .expectErrorSatisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode())
                                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(appService, never()).cancelTurn(anyString());
    }

    @Test
    void ownedConversationCanBeStopped() {
        when(appService.getConversationForUser(any(ConversationId.class), eq("user-a")))
                .thenReturn(Mono.just(mock(ConversationAppService.ConversationDetail.class)));
        when(appService.cancelTurn("c-1")).thenReturn(true);

        StepVerifier.create(controller.stop("c-1", exchange("user-a")))
                .assertNext(body -> assertThat(body.get("cancelled")).isEqualTo(true))
                .verifyComplete();
        verify(appService).cancelTurn("c-1");
    }
}
