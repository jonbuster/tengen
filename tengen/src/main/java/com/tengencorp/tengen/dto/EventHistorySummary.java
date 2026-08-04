package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.EventTimeStatus;

import java.time.Instant;

/** List representation of one ingested event without its raw payload. */
public record EventHistorySummary(
        Long id,
        String type,
        String source,
        Instant occurredAt,
        Instant receivedAt,
        Long apiKeyId,
        String apiKeyName,
        String apiKeyPrefix,
        boolean traceAvailable,
        Integer matchedRuleCount,
        Integer queuedActionCount,
        Integer suppressedActionCount,
        EventTimeStatus eventTimeStatus,
        Instant watermarkAtDecision) {

    public static EventHistorySummary from(Event event) {
        boolean traceAvailable = event.getProcessingTraceVersion() != null;
        var apiKey = event.getApiKey();
        return new EventHistorySummary(
            event.getId(),
            event.getType(),
            event.getSource(),
            event.getOccurredAt(),
            event.getReceivedAt(),
            apiKey != null ? apiKey.getId() : null,
            apiKey != null ? apiKey.getName() : null,
            apiKey != null ? apiKey.getPrefix() : null,
            traceAvailable,
            traceAvailable ? event.getMatchedRuleCount() : null,
            traceAvailable ? event.getQueuedActionCount() : null,
            traceAvailable ? event.getSuppressedActionCount() : null,
            event.getEventTimeStatus(),
            event.getWatermarkAtDecision());
    }
}
