package com.riemann.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class CsrfWebFilterTest {

    @Mock private SessionRepository sessions;

    private CsrfWebFilter filter;

    @BeforeEach
    void setUp() {
        AuthProperties props =
                new AuthProperties(
                        new AuthProperties.Sms("demo", "", "", Duration.ofSeconds(60), 5, 5),
                        new AuthProperties.Wechat(true, "", Duration.ofMinutes(5)),
                        new AuthProperties.Csrf(true));
        filter = new CsrfWebFilter(sessions, props);
    }

    @Test
    void cookieWriteWithoutTokenIsForbidden() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/agent/chat")
                                .cookie(new HttpCookie("ah_token", "sess-1")));
        AtomicBoolean passed = new AtomicBoolean(false);

        StepVerifier.create(
                        filter.filter(
                                exchange,
                                ex -> {
                                    passed.set(true);
                                    return Mono.empty();
                                }))
                .verifyComplete();

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void bearerClientIsNotSubjectToCsrf() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/agent/chat")
                                .header("Authorization", "Bearer sess-1")
                                .cookie(new HttpCookie("ah_token", "sess-1")));
        AtomicBoolean passed = new AtomicBoolean(false);

        StepVerifier.create(
                        filter.filter(
                                exchange,
                                ex -> {
                                    passed.set(true);
                                    return Mono.empty();
                                }))
                .verifyComplete();

        assertThat(passed).isTrue();
    }

    @Test
    void matchingSessionTokenAllowsTheRequest() {
        long now = System.currentTimeMillis();
        when(sessions.findById("sess-1"))
                .thenReturn(
                        Mono.just(
                                new LoginSession(
                                        "sess-1", "user-a", now, now + 60_000, "csrf-abc")));
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/agent/chat")
                                .cookie(new HttpCookie("ah_token", "sess-1"))
                                .header(CsrfWebFilter.CSRF_HEADER, "csrf-abc"));
        AtomicBoolean passed = new AtomicBoolean(false);

        StepVerifier.create(
                        filter.filter(
                                exchange,
                                ex -> {
                                    passed.set(true);
                                    return Mono.empty();
                                }))
                .verifyComplete();

        assertThat(passed).isTrue();
    }
}
