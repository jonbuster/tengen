package com.tengencorp.tengen.entity;

/** Lifecycle states for a durable webhook delivery intent. */
public enum WebhookOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_SCHEDULED,
    DELIVERED,
    DEAD_LETTER
}
