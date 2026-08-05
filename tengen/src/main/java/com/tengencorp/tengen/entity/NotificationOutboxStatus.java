package com.tengencorp.tengen.entity;

/** Durable lifecycle for an asynchronous email or SMS notification. */
public enum NotificationOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_SCHEDULED,
    SUBMITTED,
    DELIVERED,
    DEAD_LETTER,
    TEMPLATE_RENDER_ERROR
}
