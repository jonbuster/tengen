package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.TriggerMode;

import java.time.Instant;
import java.util.Map;

/** Immutable snapshot of a leased outbox row passed to the HTTP client. */
public record WebhookDeliveryAttempt(
        Long outboxId,
        String leaseToken,
        Long ruleId,
        int ruleRevision,
        String ruleName,
        String callbackUrl,
        Map<String, Object> payload,
        String scopeKey,
        TriggerMode triggerMode,
        Instant windowStart,
        Integer cooldownSeconds,
        Instant createdAt,
        int attemptNumber) {
}
