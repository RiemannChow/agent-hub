package com.riemann.agent.auth;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 登录拦截：未携带有效 token 时，
 *  - 访问 HTML 页面（/、/chat.html）重定向到 /sign_in.html
 *  - 访问受保护 API 返回 401
 * 公开路径：登录相关接口、登录页、分享只读页、公开分享快照、静态资源。
 */
@Component
@Order(-100)
public class AuthenticationWebFilter implements WebFilter {

    private final SessionRepository sessions;

    public AuthenticationWebFilter(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isPublic(exchange, path)) {
            return chain.filter(exchange);
        }
        String token = extractToken(exchange);
        if (token == null) {
            return unauthorized(exchange, path);
        }
        // 关键修复：不能再在 chain.filter 的结果上套 switchIfEmpty。
        // chain.filter 返回的是 Mono<Void>，正常放行时以“无元素完成”结束，
        // 会被 switchIfEmpty 误判为“未登录”而再次执行 unauthorized —— 此时 SSE 响应已提交，
        // 再往只读 HttpHeaders 上 add 头会抛 UnsupportedOperationException（日志里的
        // “ServerHttpResponse already committed”）。
        // 因此改为：先判定会话是否存在且未过期（得到 Mono<Boolean>），再分支决定放行或 401。
        return sessions.findById(token)
                .map(s -> s.expiresAt() >= System.currentTimeMillis())
                .defaultIfEmpty(false)
                .flatMap(authed -> authed
                        ? chain.filter(exchange)
                        : unauthorized(exchange, path));
    }

    private boolean isPublic(ServerWebExchange ex, String path) {
        if (ex.getRequest().getMethod() == HttpMethod.OPTIONS) return true;
        if (path.startsWith("/api/auth/")) return true;
        if (path.equals("/") || path.equals("/sign_in.html") || path.equals("/share.html")) return true;
        if (path.startsWith("/share/")) return true; // 重定向控制器
        // 公开分享快照（只读），仅 GET
        if (path.startsWith("/api/agent/share/") && ex.getRequest().getMethod() == HttpMethod.GET) return true;
        // A2A 协议对外暴露面（Agent Card / JSON-RPC 端点 / 服务发现视图）必须免登录，
        // 以便 Nacos 服务发现拉取 Agent Card、以及 A2A 客户端直接调用 JSON-RPC，无需 UI 登录态。
        if (path.equals("/.well-known/agent.json")) return true;
        if (path.equals("/a2a/agents")) return true;
        if (path.equals("/a2a/delegation")) return true;
        if (path.equals("/a2a")) return true;
        return false;
    }

    private String extractToken(ServerWebExchange ex) {
        String header = ex.getRequest().getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        HttpCookie cookie = ex.getRequest().getCookies().getFirst("ah_token");
        return cookie == null ? null : cookie.getValue();
    }

    private Mono<Void> unauthorized(ServerWebExchange ex, String path) {
        // 防御：若响应已提交（例如 SSE 已流出），不能再写头/体，否则会抛
        // UnsupportedOperationException。此时直接结束即可。
        if (ex.getResponse().isCommitted()) {
            return ex.getResponse().setComplete();
        }
        if (isHtmlPage(path)) {
            ex.getResponse().setStatusCode(HttpStatus.FOUND);
            ex.getResponse().getHeaders().setLocation(URI.create("/sign_in.html"));
            return ex.getResponse().setComplete();
        }
        ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        ex.getResponse().getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(body)));
    }

    private boolean isHtmlPage(String path) {
        return path.equals("/") || path.endsWith(".html");
    }
}
