package com.tengencorp.tengen.service;

import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

/** Resolves the fail-safe RabbitMQ watermark opt-out header. */
public final class RabbitMqWatermarkPolicy {

    public static final String HEADER_NAME = "x-tengen-watermark";

    private RabbitMqWatermarkPolicy() {
    }

    /**
     * Applies watermark processing unless the producer explicitly sends false.
     * Unknown header types and malformed values intentionally fail safe to true.
     */
    public static boolean shouldApply(MessageProperties properties) {
        if (properties == null) return true;
        Object value = properties.getHeaders().get(HEADER_NAME);
        if (value == null) return true;
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof byte[] bytes) return parseString(new String(bytes, StandardCharsets.UTF_8));
        if (value instanceof CharSequence chars) return parseString(chars.toString());
        return true;
    }

    private static boolean parseString(String value) {
        return !"false".equalsIgnoreCase(value.trim());
    }
}
