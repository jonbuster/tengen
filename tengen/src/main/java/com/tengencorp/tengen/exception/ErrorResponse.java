package com.tengencorp.tengen.exception;

import java.time.Instant;

/**
 * Uniform error body returned for all failed API calls.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path);
    }
}
