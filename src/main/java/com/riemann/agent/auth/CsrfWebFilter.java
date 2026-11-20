package com.riemann.agent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CSRF 防护：只对「靠 ah_token Cookie 自动携带凭证」的写请求生效。
 *
 * <p>前端使用 Authorization: Bearer 时浏览器不会自动附加凭证，天然不受 CSRF 影响，直接放行。 登录入口 /api/auth/** 在会话建立之前调用，也无需
 * CSRF token。
 */
@Component
@Order(-90)
public class CsrfWebFilter implements WebFilter {

    static final String CSRF_HEADER = "X-CSRF-Token";

    private final SessionRepository sessions;
    private final AuthProperties properties;

    public CsrfWebFilter(SessionRepository sessions, AuthProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled() || !isStateChanging(exchange)) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/api/auth/")) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst("ah_token");
        if (cookie == null) {
            return chain.filter(exchange);
        }
        String supplied = exchange.getRequest().getHeaders().getFirst(CSRF_HEADER);
        if (supplied == null || supplied.isBlank()) {
            return forbidden(exchange);
        }
        return sessions.findById(cookie.getValue())
                .map(
                        session ->
                                session.csrfToken() != null
                                        && MessageDigest.isEqual(
                                                session.csrfToken()
                                                        .getBytes(StandardCharsets.UTF_8),
                                                supplied.getBytes(StandardCharsets.UTF_8)))
                .defaultIfEmpty(false)
                .flatMap(
                        valid ->
                                Boolean.TRUE.equals(valid)
                                        ? chain.filter(exchange)
                                        : forbidden(exchange));
    }

    private boolean enabled() {
        AuthProperties.Csrf cfg = properties.csrf();
        return cfg == null || cfg.enabled();
    }

    private boolean isStateChanging(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        return method == HttpMethod.POST
                || method == HttpMethod.PUT
                || method == HttpMethod.PATCH
                || method == HttpMethod.DELETE;
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
}
