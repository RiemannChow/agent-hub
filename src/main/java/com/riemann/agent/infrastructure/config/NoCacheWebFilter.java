package com.riemann.agent.infrastructure.config;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 静态资源（chat.html / sign_in.html 等）禁用缓存：前端 A2UI 等改动上线后，
 * 浏览器必须拉取最新文件，否则会一直加载旧版本导致“表格不渲染”等表象问题。
 * 这里统一对所有响应加 Cache-Control: no-store，开发期简单可靠。
 */
@Component
@Order(-50)
public class NoCacheWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        exchange.getResponse().getHeaders().add(HttpHeaders.PRAGMA, "no-cache");
        return chain.filter(exchange);
    }
}
