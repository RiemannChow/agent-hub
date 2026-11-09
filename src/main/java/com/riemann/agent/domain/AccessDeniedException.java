package com.riemann.agent.domain;

/** Raised when a caller tries to read or continue a conversation owned by another user. */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
