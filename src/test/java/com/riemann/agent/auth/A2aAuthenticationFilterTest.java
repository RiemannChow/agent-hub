package com.riemann.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.infrastructure.a2a.A2aAuthProperties;
import com.riemann.agent.infrastructure.a2a.A2aSignature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Verifies that /a2a rejects unsigned or tampered requests (issue #25). */
class A2aAuthenticationFilterTest {

    private static final String SECRET = "unit-test-secret";
    private static final String AGENT = "agent-a";
    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/get\"}";

    private static A2aAuthProperties props(String secret) {
        A2aAuthProperties p = new A2aAuthProperties();
        p.setSharedSecret(secret);
        return p;
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static MockServerWebExchange request(
            String path, String body, String agentId, String timestamp, String signature) {
        MockServerHttpRequest.BodyBuilder builder =
                MockServerHttpRequest.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(A2aSignature.AGENT_ID_HEADER, agentId)
                        .header(A2aSignature.TIMESTAMP_HEADER, timestamp)
                        .header(A2aSignature.SIGNATURE_HEADER, signature);
        return MockServerWebExchange.from(builder.body(body));
    }

    private static MockServerWebExchange signedRequest(String body, long timestamp) {
        String sig =
                A2aSignature.sign(
                        SECRET,
                        "POST",
                        "/a2a",
                        Long.toString(timestamp),
                        AGENT,
                        body.getBytes(StandardCharsets.UTF_8));
        return request("/a2a", body, AGENT, Long.toString(timestamp), sig);
    }

    private static WebFilterChain recordingChain(
            AtomicBoolean called, AtomicReference<String> body, AtomicReference<String> agentId) {
        return ex -> {
            called.set(true);
            agentId.set(AuthenticatedAgent.require(ex));
            return DataBufferUtils.join(ex.getRequest().getBody())
                    .map(A2aAuthenticationFilterTest::toBytes)
                    .doOnNext(bytes -> body.set(new String(bytes, StandardCharsets.UTF_8)))
                    .then();
        };
    }

    private static byte[] toBytes(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    private static Mono<Void> run(
            A2aAuthenticationFilter filter, ServerWebExchange ex, WebFilterChain chain) {
        return filter.filter(ex, chain);
    }

    @Test
    void rejectsAllRequestsWhenSecretIsNotConfigured() {
        A2aAuthenticationFilter filter = new A2aAuthenticationFilter(props(""));
        MockServerWebExchange ex = signedRequest(BODY, now());
        AtomicBoolean called = new AtomicBoolean();

        run(filter, ex, recordingChain(called, new AtomicReference<>(), new AtomicReference<>()))
                .block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(called).isFalse();
    }

    @Test
    void rejectsMissingAndInvalidSignatures() {
        A2aAuthenticationFilter filter = new A2aAuthenticationFilter(props(SECRET));
        AtomicBoolean called = new AtomicBoolean();

        MockServerWebExchange missing = request("/a2a", BODY, AGENT, Long.toString(now()), "");
        run(
                        filter,
                        missing,
                        recordingChain(called, new AtomicReference<>(), new AtomicReference<>()))
                .block();
        assertThat(missing.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        MockServerWebExchange wrong =
                request("/a2a", BODY, AGENT, Long.toString(now()), "deadbeef");
        run(filter, wrong, recordingChain(called, new AtomicReference<>(), new AtomicReference<>()))
                .block();
        assertThat(wrong.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(called).isFalse();
    }

    @Test
    void rejectsStaleTimestamp() {
        A2aAuthenticationFilter filter = new A2aAuthenticationFilter(props(SECRET));
        long stale = now() - 3600;
        MockServerWebExchange ex = signedRequest(BODY, stale);

        run(
                        filter,
                        ex,
                        recordingChain(
                                new AtomicBoolean(),
                                new AtomicReference<>(),
                                new AtomicReference<>()))
                .block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTamperedAgentId() {
        A2aAuthenticationFilter filter = new A2aAuthenticationFilter(props(SECRET));
        long ts = now();
        String sig =
                A2aSignature.sign(
                        SECRET,
                        "POST",
                        "/a2a",
                        Long.toString(ts),
                        AGENT,
                        BODY.getBytes(StandardCharsets.UTF_8));
        MockServerWebExchange ex = request("/a2a", BODY, "agent-b", Long.toString(ts), sig);

        run(
                        filter,
                        ex,
                        recordingChain(
                                new AtomicBoolean(),
                                new AtomicReference<>(),
                                new AtomicReference<>()))
                .block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void acceptsValidSignatureBindsIdentityAndKeepsBodyReadable() {
        A2aAuthenticationFilter filter = new A2aAuthenticationFilter(props(SECRET));
        MockServerWebExchange ex = signedRequest(BODY, now());
        AtomicBoolean called = new AtomicBoolean();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> agentId = new AtomicReference<>();

        run(filter, ex, recordingChain(called, body, agentId)).block();

        assertThat(called).isTrue();
        assertThat(agentId.get()).isEqualTo(AGENT);
        assertThat(body.get()).isEqualTo(BODY);
        assertThat(ex.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsReplayOfTheSameSignature() {
        A2aAuthenticationFilter filter = new A2aAuthenticationFilter(props(SECRET));
        long ts = now();
        WebFilterChain chain =
                recordingChain(
                        new AtomicBoolean(), new AtomicReference<>(), new AtomicReference<>());

        MockServerWebExchange first = signedRequest(BODY, ts);
        run(filter, first, chain).block();
        MockServerWebExchange second = signedRequest(BODY, ts);
        run(filter, second, chain).block();

        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void leavesNonA2aPathsUntouched() {
        A2aAuthenticationFilter filter = new A2aAuthenticationFilter(props(SECRET));
        MockServerWebExchange ex = request("/api/agent/chat", BODY, "", "", "");
        AtomicBoolean called = new AtomicBoolean();
        WebFilterChain chain =
                ex2 -> {
                    called.set(true);
                    return Mono.empty();
                };

        run(filter, ex, chain).block();

        assertThat(called).isTrue();
        assertThat(ex.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
