package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.IngestionOrigin;
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
        Instant watermarkAtDecision,
        IngestionOrigin ingestionOrigin,
        Long connectorId,
        String connectorName) {

    public EventHistorySummary(Long id, String type, String source, Instant occurredAt,
                                Instant receivedAt, Long apiKeyId, String apiKeyName,
                                String apiKeyPrefix, boolean traceAvailable,
                                Integer matchedRuleCount, Integer queuedActionCount,
                                Integer suppressedActionCount, EventTimeStatus eventTimeStatus,
                                Instant watermarkAtDecision) {
        this(id, type, source, occurredAt, receivedAt, apiKeyId, apiKeyName, apiKeyPrefix,
            traceAvailable, matchedRuleCount, queuedActionCount, suppressedActionCount,
            eventTimeStatus, watermarkAtDecision, IngestionOrigin.HTTP, null, null);
    }

    public static EventHistorySummary from(Event event) {
        boolean traceAvailable = event.getProcessingTraceVersion() != null;
        var apiKey = event.getApiKey();
        var connector = event.getRabbitMqConnector();
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
            event.getWatermarkAtDecision(),
            event.getIngestionOrigin() != null ? event.getIngestionOrigin() : IngestionOrigin.HTTP,
            connector != null ? connector.getId() : null,
            connector != null ? connector.getDisplayName() : null);
    }
}
