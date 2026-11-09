package com.riemann.agent.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * The only source of truth for the caller's identity inside a request.
 *
 * <p>{@link AuthenticationWebFilter} resolves the session and binds {@link LoginSession#userId()}
 * to the {@link ServerWebExchange} attributes. Controllers must read the user id through {@link
 * #require(ServerWebExchange)} instead of accepting it from a request body, a query parameter or a
 * header. A client-supplied id can never be trusted: anything read from the wire would let one user
 * act as another (see issue #19).
 */
public final class AuthenticatedUser {

    /** Exchange attribute key holding the authenticated user id. */
    public static final String KEY = AuthenticatedUser.class.getName() + ".userId";

    private AuthenticatedUser() {}

    /** Called only by {@link AuthenticationWebFilter} after the session has been validated. */
    public static void bind(ServerWebExchange exchange, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Cannot bind a blank user id");
        }
        exchange.getAttributes().put(KEY, userId);
    }

    /**
     * @return the authenticated user id
     * @throws ResponseStatusException 401 when the request has no bound identity, which means a
     *     controller was reached without passing through the authentication filter
     */
    public static String require(ServerWebExchange exchange) {
        Object value = exchange.getAttributes().get(KEY);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is missing");
    }
}
