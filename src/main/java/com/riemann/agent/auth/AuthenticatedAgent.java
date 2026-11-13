package com.riemann.agent.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * Holds the <b>verified</b> A2A peer identity for the duration of a request.
 *
 * <p>It is bound only by {@code A2aAuthenticationFilter} after a valid HMAC signature has been
 * checked. Controllers must read the agent id from here and never from a raw {@code X-Agent-Id}
 * header, which is an untrusted claim.
 */
public final class AuthenticatedAgent {

    private static final String KEY = AuthenticatedAgent.class.getName() + ".agentId";

    private AuthenticatedAgent() {}

    public static void bind(ServerWebExchange exchange, String agentId) {
        exchange.getAttributes().put(KEY, agentId);
    }

    public static String require(ServerWebExchange exchange) {
        Object value = exchange.getAttributes().get(KEY);
        if (!(value instanceof String agentId) || agentId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "A2A peer is not authenticated");
        }
        return agentId;
    }
}
