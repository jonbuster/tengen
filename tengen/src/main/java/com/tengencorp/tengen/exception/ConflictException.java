package com.tengencorp.tengen.exception;

/** Thrown when an admin action conflicts with the current resource state. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
