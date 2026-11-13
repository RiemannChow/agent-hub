package com.riemann.agent.auth;

import com.riemann.agent.infrastructure.a2a.A2aAuthProperties;
import com.riemann.agent.infrastructure.a2a.A2aSignature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies HMAC-SHA256 signatures on every {@code POST /a2a} request.
 *
 * <p>Ordered just after {@link AuthenticationWebFilter} (-100): that filter lets {@code /a2a}
 * through without a UI login, and this one performs the actual service-to-service check. The
 * verified agent id is bound to the exchange via {@link AuthenticatedAgent} so controllers never
 * read an untrusted {@code X-Agent-Id} header.
 *
 * <p>The body is buffered once for verification and replayed through a request decorator, so
 * {@code @RequestBody String} still sees the original JSON. Signatures are tracked for the skew
 * window to reject replayed requests.
 *
 * <p>Fail closed: when {@code agent.a2a.auth.shared-secret} is unset, every {@code /a2a} call is
 * rejected. {@code /.well-known/agent.json} is not touched and stays public per the A2A spec.
 */
@Component
@Order(-99)
public class A2aAuthenticationFilter implements WebFilter {

    private static final String A2A_PATH = "/a2a";
    private static final String AGENT_ID_PATTERN = "[A-Za-z0-9._:-]{1,128}";

    private final A2aAuthProperties props;
    private final Map<String, Long> seenSignatures = new ConcurrentHashMap<>();

    public A2aAuthenticationFilter(A2aAuthProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!A2A_PATH.equals(request.getURI().getPath())
                || request.getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }
        if (!props.isConfigured()) {
            return unauthorized(exchange, "A2A shared secret is not configured");
        }

        HttpHeaders headers = request.getHeaders();
        String agentId = headers.getFirst(A2aSignature.AGENT_ID_HEADER);
        String timestamp = headers.getFirst(A2aSignature.TIMESTAMP_HEADER);
        String signature = headers.getFirst(A2aSignature.SIGNATURE_HEADER);
        if (agentId == null || !agentId.matches(AGENT_ID_PATTERN)) {
            return unauthorized(exchange, "Missing or invalid X-Agent-Id");
        }
        if (!timestampWithinSkew(timestamp)) {
            return unauthorized(exchange, "Missing or stale X-Agent-Timestamp");
        }
        if (signature == null || signature.isBlank()) {
            return unauthorized(exchange, "Missing X-Agent-Signature");
        }

        return DataBufferUtils.join(request.getBody())
                .map(A2aAuthenticationFilter::toBytes)
                .defaultIfEmpty(new byte[0])
                .flatMap(
                        body -> {
                            if (!A2aSignature.verify(
                                    props.getSharedSecret(),
                                    HttpMethod.POST.name(),
                                    A2A_PATH,
                                    timestamp,
                                    agentId,
                                    body,
                                    signature)) {
                                return unauthorized(exchange, "Invalid A2A signature");
                            }
                            if (!registerNonce(signature)) {
                                return unauthorized(exchange, "A2A request replayed");
                            }
                            ServerWebExchange decorated =
                                    exchange.mutate()
                                            .request(
                                                    new ServerHttpRequestDecorator(request) {
                                                        @Override
                                                        public Flux<DataBuffer> getBody() {
                                                            return Flux.defer(
                                                                    () ->
                                                                            Flux.just(
                                                                                    exchange.getResponse()
                                                                                            .bufferFactory()
                                                                                            .wrap(
                                                                                                    body)));
                                                        }
                                                    })
                                            .build();
                            AuthenticatedAgent.bind(decorated, agentId);
                            return chain.filter(decorated);
                        });
    }

    private boolean timestampWithinSkew(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            long sent = Long.parseLong(raw.trim());
            long now = Instant.now().getEpochSecond();
            return Math.abs(now - sent) <= props.getAllowedSkew().getSeconds();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Returns false when the exact signature was already used inside the skew window. */
    private boolean registerNonce(String signature) {
        long now = Instant.now().getEpochSecond();
        long maxAge = props.getAllowedSkew().getSeconds() * 2 + 60;
        seenSignatures.entrySet().removeIf(e -> now - e.getValue() > maxAge);
        return seenSignatures.putIfAbsent(signature, now) == null;
    }

    private static byte[] toBytes(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        if (exchange.getResponse().isCommitted()) {
            return exchange.getResponse().setComplete();
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body =
                ("{\"error\":\"unauthorized\",\"message\":\"" + reason + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
