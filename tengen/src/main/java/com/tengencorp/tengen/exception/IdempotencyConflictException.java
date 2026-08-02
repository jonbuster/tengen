package com.tengencorp.tengen.exception;

/** Indicates that an idempotency key was reused incompatibly or is in progress. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
