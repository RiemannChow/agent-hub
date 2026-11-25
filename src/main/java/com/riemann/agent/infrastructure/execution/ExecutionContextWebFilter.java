package com.riemann.agent.infrastructure.execution;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Creates or propagates a request correlation id and exposes the context to downstream Reactor
 * operators. This is the first step towards end-to-end trace propagation; log MDC wiring is added
 * in a later slice.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExecutionContextWebFilter implements WebFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = ExecutionContext.newId();
        }
        ExecutionContext context = ExecutionContext.forRequest(traceId, null, null);
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(REQUEST_ID_HEADER, context.traceId());
        return chain.filter(exchange).contextWrite(ExecutionContextHolder.contextWrite(context));
    }
}
