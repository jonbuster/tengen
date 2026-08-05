package com.tengencorp.tengen.service;

/** Provider response classification used by the notification worker. */
public record NotificationProviderResult(
        boolean successful,
        boolean retryable,
        String providerMessageId,
        String category,
        String error) {

    public static NotificationProviderResult success(String providerMessageId) {
        return new NotificationProviderResult(true, false, providerMessageId, null, null);
    }

    public static NotificationProviderResult failure(boolean retryable, String category, String error) {
        return new NotificationProviderResult(false, retryable, null, category, error);
    }
}
