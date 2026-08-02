package com.tengencorp.tengen.service;

/** Result of exactly one HTTP delivery attempt. */
public record WebhookDeliveryResult(
        boolean successful,
        boolean retryable,
        Integer statusCode,
        String error,
        long durationMs) {

    public static WebhookDeliveryResult success(Integer statusCode, long durationMs) {
        return new WebhookDeliveryResult(true, false, statusCode, null, durationMs);
    }

    public static WebhookDeliveryResult failure(boolean retryable, Integer statusCode,
                                                String error, long durationMs) {
        return new WebhookDeliveryResult(false, retryable, statusCode, error, durationMs);
    }
}
