package com.riemann.agent.infrastructure.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.ContextView;

class ExecutionContextWebFilterTest {

    @Test
    void propagatesExistingRequestIdIntoReactorContext() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/chat")
                                .header(ExecutionContextWebFilter.REQUEST_ID_HEADER, "trace-123")
                                .build());
        AtomicReference<ContextView> captured = new AtomicReference<>();
        WebFilterChain chain =
                next ->
                        Mono.deferContextual(
                                context -> {
                                    captured.set(context);
                                    return Mono.empty();
                                });

        StepVerifier.create(new ExecutionContextWebFilter().filter(exchange, chain))
                .verifyComplete();

        assertEquals(
                "trace-123",
                exchange.getResponse()
                        .getHeaders()
                        .getFirst(ExecutionContextWebFilter.REQUEST_ID_HEADER));
        assertTrue(ExecutionContextHolder.current(captured.get()).isPresent());
        assertEquals(
                "trace-123",
                ExecutionContextHolder.current(captured.get()).orElseThrow().traceId());
    }

    @Test
    void generatesRequestIdWhenMissing() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/chat").build());
        WebFilterChain chain = next -> Mono.empty();

        StepVerifier.create(new ExecutionContextWebFilter().filter(exchange, chain))
                .verifyComplete();

        String header =
                exchange.getResponse()
                        .getHeaders()
                        .getFirst(ExecutionContextWebFilter.REQUEST_ID_HEADER);
        assertTrue(header != null && header.matches("[0-9a-f]{32}"));
    }
}
