package com.tengencorp.tengen.dto;

import java.util.List;

/** Stable page response for the delivery-history admin API. */
public record WebhookDeliveryPage(
        List<WebhookDeliverySummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
