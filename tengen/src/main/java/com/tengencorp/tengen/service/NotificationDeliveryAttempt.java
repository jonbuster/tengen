package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.TriggerMode;

import java.time.Instant;
import java.util.Map;

public record NotificationDeliveryAttempt(
        Long outboxId,
        String leaseToken,
        Long ruleId,
        int ruleRevision,
        String ruleName,
        Long destinationId,
        NotificationChannel channel,
        String provider,
        Map<String, Object> messageSnapshot,
        String scopeKey,
        TriggerMode triggerMode,
        Instant windowStart,
        Integer cooldownSeconds,
        Instant createdAt,
        int attemptNumber) {
}
