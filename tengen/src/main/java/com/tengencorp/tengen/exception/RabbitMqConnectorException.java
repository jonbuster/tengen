package com.tengencorp.tengen.exception;

/** Safe connector failure carrying only a fixed operational category. */
public class RabbitMqConnectorException extends RuntimeException {

    private final String category;

    public RabbitMqConnectorException(String category, String message) {
        super(message);
        this.category = category;
    }

    public RabbitMqConnectorException(String category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
