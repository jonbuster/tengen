package com.tengencorp.tengen.exception;

/**
 * Thrown when a requested resource does not exist. Mapped to HTTP 404 by
 * {@link ApiExceptionHandler}.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
