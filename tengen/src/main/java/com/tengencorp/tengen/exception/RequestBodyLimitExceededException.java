package com.tengencorp.tengen.exception;

import java.io.IOException;

public class RequestBodyLimitExceededException extends IOException {
    public RequestBodyLimitExceededException(String message) {
        super(message);
    }
}
