package com.riemann.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

class AuthenticatedUserTest {

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/agent/chat"));
    }

    @Test
    void boundIdentityIsReturned() {
        MockServerWebExchange exchange = exchange();
        AuthenticatedUser.bind(exchange, "user-42");
        assertThat(AuthenticatedUser.require(exchange)).isEqualTo("user-42");
    }

    @Test
    void missingIdentityIsRejected() {
        assertThatThrownBy(() -> AuthenticatedUser.require(exchange()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode())
                                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void blankIdentityCannotBeBound() {
        assertThatThrownBy(() -> AuthenticatedUser.bind(exchange(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
