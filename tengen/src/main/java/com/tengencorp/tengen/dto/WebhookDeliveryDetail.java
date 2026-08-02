package com.tengencorp.tengen.dto;

import java.util.Map;
import java.time.Instant;

/** Detail representation of one webhook delivery, including its stored payload. */
public record WebhookDeliveryDetail(
        WebhookDeliverySummary delivery,
        String callbackUrl,
        Map<String, Object> payload,
        String deduplicationKey,
        Instant leaseExpiresAt) {
}
