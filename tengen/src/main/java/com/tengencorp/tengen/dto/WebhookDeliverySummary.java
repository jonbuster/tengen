package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.TriggerMode;
import com.tengencorp.tengen.entity.WebhookOutbox;
import com.tengencorp.tengen.entity.WebhookOutboxStatus;

import java.time.Instant;

/** Safe list representation of a webhook delivery record. */
public record WebhookDeliverySummary(
        Long id,
        WebhookOutboxStatus status,
        Long ruleId,
        String ruleName,
        Long eventId,
        String destination,
        String scopeKey,
        TriggerMode triggerMode,
        Instant windowStart,
        Integer attemptCount,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Instant deliveredAt,
        Integer lastStatusCode,
        String lastError,
        Instant createdAt,
        Instant manuallyRetriedAt) {

    public static WebhookDeliverySummary from(WebhookOutbox outbox, String destination) {
        return new WebhookDeliverySummary(
            outbox.getId(),
            outbox.getStatus(),
            outbox.getRuleId(),
            outbox.getRuleName(),
            outbox.getEvent().getId(),
            destination,
            outbox.getScopeKey() == null || outbox.getScopeKey().isBlank()
                ? null
                : outbox.getScopeKey(),
            outbox.getTriggerMode(),
            outbox.getWindowStart(),
            outbox.getAttemptCount(),
            outbox.getNextAttemptAt(),
            outbox.getLastAttemptAt(),
            outbox.getDeliveredAt(),
            outbox.getLastStatusCode(),
            outbox.getLastError(),
            outbox.getCreatedAt(),
            outbox.getManuallyRetriedAt());
    }
}
