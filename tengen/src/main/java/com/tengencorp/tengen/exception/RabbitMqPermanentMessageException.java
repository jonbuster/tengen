package com.tengencorp.tengen.exception;

/** A message-local failure that belongs on the configured dead-letter route. */
public class RabbitMqPermanentMessageException extends RuntimeException {

    private final String category;

    public RabbitMqPermanentMessageException(String category, String message) {
        super(message);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
