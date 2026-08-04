package com.tengencorp.tengen.service;

import com.tengencorp.tengen.repository.ApiKeyRepository;
import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.dto.EventResponse;
import com.tengencorp.tengen.dto.CompactEventResponse;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.EventRuleActionOutcome;
import com.tengencorp.tengen.entity.EventRuleOutcome;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.repository.EventRepository;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleActionWindow;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.TriggerMode;
import com.tengencorp.tengen.dto.AggregateResult;
import com.tengencorp.tengen.dto.RuleEvaluation;
import com.tengencorp.tengen.dto.SequenceResult;
import com.tengencorp.tengen.entity.EventIdempotency;
import com.tengencorp.tengen.entity.EventIdempotencyStatus;
import com.tengencorp.tengen.entity.ResponseMode;
import com.tengencorp.tengen.entity.EventTimeStatus;
import com.tengencorp.tengen.entity.IngestionOrigin;
import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.exception.IdempotencyConflictException;
import com.tengencorp.tengen.exception.RabbitMqConnectorException;
import com.tengencorp.tengen.exception.RabbitMqPermanentMessageException;
import com.tengencorp.tengen.helper.EventRequestHasher;
import com.tengencorp.tengen.repository.EventIdempotencyRepository;
import com.tengencorp.tengen.repository.EventRuleOutcomeRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates event processing: persist the event, evaluate it against every
 * active rule, queue webhook delivery intents for matches, and build the API response.
 */
@Service
public class EventService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final EventRepository eventRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final RuleRepository ruleRepository;
    private final RuleEngine ruleEngine;
    private final AbsenceRuleService absenceRuleService;
    private final WebhookOutboxService webhookOutboxService;
    private final WebhookCooldownService webhookCooldownService;
    private final ApiKeyService apiKeyService;
    private final EventIdempotencyRepository eventIdempotencyRepository;
    private final EventRuleOutcomeRepository eventRuleOutcomeRepository;
    private final EventRequestHasher eventRequestHasher;
    private final ObjectMapper objectMapper;
    private final EventWatermarkService eventWatermarkService;
    private final Counter acceptedEvents;
    private final Counter matchedEvents;
    private final Counter replayedEvents;
    private final Counter onTimeEvents;
    private final Counter lateAcceptedEvents;
    private final Counter tooLateEvents;
    private final long maxFutureSkewSeconds;

    public EventService(EventRepository eventRepository, ApiKeyRepository apiKeyRepository,
                        RuleRepository ruleRepository, RuleEngine ruleEngine,
                        AbsenceRuleService absenceRuleService,
                        WebhookOutboxService webhookOutboxService,
                        WebhookCooldownService webhookCooldownService, ApiKeyService apiKeyService,
                        EventIdempotencyRepository eventIdempotencyRepository,
                        EventRequestHasher eventRequestHasher, ObjectMapper objectMapper,
                        MeterRegistry meterRegistry,
                        @Value("${tengen.ingestion.max-future-skew-seconds:300}")
                        long maxFutureSkewSeconds,
                        EventRuleOutcomeRepository eventRuleOutcomeRepository,
                        EventWatermarkService eventWatermarkService) {
        this.eventRepository = eventRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.ruleRepository = ruleRepository;
        this.ruleEngine = ruleEngine;
        this.absenceRuleService = absenceRuleService;
        this.webhookOutboxService = webhookOutboxService;
        this.webhookCooldownService = webhookCooldownService;
        this.apiKeyService = apiKeyService;
        this.eventIdempotencyRepository = eventIdempotencyRepository;
        this.eventRuleOutcomeRepository = eventRuleOutcomeRepository;
        this.eventRequestHasher = eventRequestHasher;
        this.objectMapper = objectMapper;
        this.eventWatermarkService = eventWatermarkService;
        this.acceptedEvents = meterRegistry.counter("tengen.ingestion.events", "result", "accepted");
        this.matchedEvents = meterRegistry.counter("tengen.ingestion.events", "result", "matched");
        this.replayedEvents = meterRegistry.counter("tengen.ingestion.events", "result", "replayed");
        this.onTimeEvents = meterRegistry.counter("tengen.ingestion.event_time", "status", "on_time");
        this.lateAcceptedEvents = meterRegistry.counter(
            "tengen.ingestion.event_time", "status", "late_accepted");
        this.tooLateEvents = meterRegistry.counter(
            "tengen.ingestion.event_time", "status", "too_late");
        this.maxFutureSkewSeconds = maxFutureSkewSeconds;
    }

    @Transactional
    public EventResponse process(EventRequest request) {
        return process(request, null);
    }

    @Transactional
    public EventResponse process(EventRequest request, Long apiKeyId) {
        return process(request, apiKeyId, null);
    }

    @Transactional
    public EventResponse process(EventRequest request, Long apiKeyId, String idempotencyKey) {
        EventIngestionResult result = processWithMetadata(request, apiKeyId, idempotencyKey);
        return result.fullResponse() != null
            ? result.fullResponse()
            : objectMapper.convertValue(result.responseBody(), EventResponse.class);
    }

    /**
     * Process an event and return the response body selected by the API key,
     * together with whether the body came from a completed idempotency replay.
     */
    @Transactional
    public EventIngestionResult processWithMetadata(EventRequest request, Long apiKeyId,
                                                    String idempotencyKey) {
        if (apiKeyId == null) {
            throw new AccessDeniedException("API key is required");
        }
        if (request.timestamp() != null
            && request.timestamp().isAfter(Instant.now().plusSeconds(maxFutureSkewSeconds))) {
            throw new IllegalArgumentException(
                "Event timestamp is too far in the future");
        }

        ApiKey apiKey = apiKeyRepository.findById(apiKeyId)
            .orElseThrow(() -> new AccessDeniedException("API key is invalid"));

        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String requestHash = normalizedKey != null ? eventRequestHasher.hash(request) : null;

        Event event = new Event(request.type(), request.source(),
            request.timestamp() != null ? request.timestamp() : Instant.now(), request.data(),
            apiKey);
        if (!apiKeyService.isValid(apiKey, event)) {
            throw new AccessDeniedException("API key is not allowed for this event");
        }

        if (normalizedKey == null) {
            ProcessingResult result = processEvent(request, apiKey);
            return project(result.response(), apiKey, false);
        }

        int inserted = eventIdempotencyRepository.insertIfAbsent(
            apiKeyId,
            normalizedKey,
            requestHash,
            EventIdempotencyStatus.PROCESSING.name(),
            Instant.now());
        EventIdempotency idempotency = eventIdempotencyRepository
            .findByApiKeyIdAndIdempotencyKey(apiKeyId, normalizedKey)
            .orElseThrow(() -> new IllegalStateException("Idempotency record was not created"));

        if (inserted == 0) {
            if (!requestHash.equals(idempotency.getRequestHash())) {
                throw new IdempotencyConflictException(
                    "Idempotency-Key has already been used with a different event payload");
            }
            if (idempotency.getStatus() == EventIdempotencyStatus.COMPLETED) {
                replayedEvents.increment();
                return new EventIngestionResult(replayResponse(idempotency), true, null);
            }
            throw new IdempotencyConflictException(
                "The request for this Idempotency-Key is still being processed; retry later");
        }

        ProcessingResult result = processEvent(request, apiKey);
        EventIngestionResult ingestionResult = project(result.response(), apiKey, false);
        idempotency.setEvent(result.event());
        idempotency.setResponsePayload(toPayload(ingestionResult.responseBody()));
        idempotency.setStatus(EventIdempotencyStatus.COMPLETED);
        idempotency.setCompletedAt(Instant.now());
        return ingestionResult;
    }

    /**
     * Processes a validated broker message without HTTP idempotency or response
     * projection. Receipt reservation is owned by the RabbitMQ delivery service.
     */
    @Transactional
    public EventIngestionResult processRabbitMq(EventRequest request, Long apiKeyId,
                                                RabbitMqConnector connector) {
        return processRabbitMq(request, apiKeyId, connector, true);
    }

    /**
     * Processes a RabbitMQ event with optional event-time watermarking. The
     * default path keeps watermarking enabled for compatibility.
     */
    @Transactional
    public EventIngestionResult processRabbitMq(EventRequest request, Long apiKeyId,
                                                RabbitMqConnector connector,
                                                boolean applyWatermark) {
        if (apiKeyId == null) {
            throw new RabbitMqConnectorException("API_KEY_INVALID", "The connector API key is invalid");
        }
        if (request.timestamp() != null
                && request.timestamp().isAfter(Instant.now().plusSeconds(maxFutureSkewSeconds))) {
            throw new RabbitMqPermanentMessageException("FUTURE_TIMESTAMP",
                "The event timestamp is too far in the future");
        }

        ApiKey apiKey = apiKeyRepository.findById(apiKeyId)
            .orElseThrow(() -> new RabbitMqConnectorException(
                "API_KEY_INVALID", "The connector API key is invalid"));
        if (!apiKey.isActive()
                || (apiKey.getExpiresAt() != null && !apiKey.getExpiresAt().isAfter(Instant.now()))) {
            throw new RabbitMqConnectorException("API_KEY_REVOKED",
                "The connector API key is inactive or expired");
        }

        Event authorizationEvent = new Event(request.type(), request.source(),
            request.timestamp() != null ? request.timestamp() : Instant.now(), request.data(), apiKey);
        if (!apiKeyService.isValid(apiKey, authorizationEvent)) {
            throw new RabbitMqPermanentMessageException("API_KEY_SCOPE_REJECTED",
                "The event is outside the connector API key scope");
        }

        ProcessingResult result = processEvent(
            request, apiKey, IngestionOrigin.RABBITMQ, connector, applyWatermark);
        return new EventIngestionResult(result.response(), false, result.response(), result.event());
    }

    private ProcessingResult processEvent(EventRequest request, ApiKey apiKey) {
        return processEvent(request, apiKey, IngestionOrigin.HTTP, null, true);
    }

    private ProcessingResult processEvent(EventRequest request, ApiKey apiKey,
                                          IngestionOrigin origin,
                                          RabbitMqConnector connector,
                                          boolean applyWatermark) {
        Instant occurredAt = request.timestamp() != null ? request.timestamp() : Instant.now();
        EventTimeDecision eventTimeDecision = applyWatermark
            ? eventWatermarkService.classify(request.type(), request.source(), occurredAt)
            : null;
        Event event = new Event(request.type(), request.source(), occurredAt, request.data(),
            apiKey);
        event.setIngestionOrigin(origin);
        event.setRabbitMqConnector(connector);
        event.setWatermarkApplied(applyWatermark);
        event.setEventTimeStatus(eventTimeDecision != null ? eventTimeDecision.status() : null);
        event.setWatermarkAtDecision(
            eventTimeDecision != null ? eventTimeDecision.watermarkAtDecision() : null);
        event = eventRepository.save(event);

        if (eventTimeDecision != null && eventTimeDecision.status() == EventTimeStatus.TOO_LATE) {
            recordEventTimeMetric(eventTimeDecision.status());
            acceptedEvents.increment();
            event.recordProcessingTrace(0, 0, 0);
            return new ProcessingResult(
                event,
                new EventResponse(
                    request,
                    "accepted",
                    false,
                    List.of(),
                    List.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    eventTimeDecision.status()));
        }

        List<Rule> activeRules = ruleRepository.findActiveRulesForEvent(
            event.getType(), event.getSource(), RuleType.SEQUENCE, RuleType.ABSENCE);
        List<String> matchedRuleNames = new ArrayList<>();
        List<String> queuedRuleNames = new ArrayList<>();
        List<String> suppressedRuleNames = new ArrayList<>();
        Map<String, AggregateResult> aggregates = new LinkedHashMap<>();
        Map<String, SequenceResult> sequences = new LinkedHashMap<>();
        List<EventRuleOutcome> outcomes = new ArrayList<>();

        for (Rule rule : activeRules) {
            if (rule.getRuleType() == RuleType.ABSENCE) {
                // Absence starts and satisfactions are durable progress changes,
                // not logical matches. The delayed match is finalized by the
                // absence worker after the expected stream watermark closes.
                absenceRuleService.process(event, rule);
                continue;
            }
            RuleEvaluation evaluation = ruleEngine.evaluate(event, rule);
            boolean currentMatch = evaluation.matched(rule);
            if (isEdgeWebhook(rule) && hasTriggerScope(event, rule, evaluation)) {
                if (!currentMatch) {
                    resetEdgeState(rule, evaluation.groupKey());
                }
            }

            if (!currentMatch) {
                continue;
            }

            matchedRuleNames.add(rule.getName());

            AggregateResult aggregateResult = null;
            if (rule.getRuleType() == RuleType.AGGREGATE) {
                aggregateResult = new AggregateResult(
                    rule.getRuleType().name(),
                    rule.getAggType() != null ? rule.getAggType().name() : "",
                    evaluation.aggregateValue() != null ? evaluation.aggregateValue() : 0.0,
                    rule.getThreshold(),
                    rule.getWindowSeconds() != null ? rule.getWindowSeconds() : 0,
                    evaluation.groupKey()
                );
                aggregates.put(rule.getName(), aggregateResult);
            }
            if (rule.getRuleType() == RuleType.SEQUENCE && evaluation.sequence() != null) {
                sequences.put(rule.getName(), evaluation.sequence());
            }

            ActionDecision actionDecision = new ActionDecision(EventRuleActionOutcome.LOG_ONLY, null, null);
            if (rule.getAction() == RuleAction.WEBHOOK && rule.getCallbackUrl() != null) {
                actionDecision = enqueueWebhook(
                    rule, event, request, aggregateResult, evaluation.sequence(), evaluation.groupKey());
                if (actionDecision.outcome() == EventRuleActionOutcome.WEBHOOK_QUEUED) {
                    queuedRuleNames.add(rule.getName());
                } else if (actionDecision.outcome() == EventRuleActionOutcome.WEBHOOK_SUPPRESSED) {
                    suppressedRuleNames.add(rule.getName());
                }
            }
            outcomes.add(new EventRuleOutcome(
                event,
                rule.getId(),
                rule.getEffectiveRevision(),
                rule.getName(),
                rule.getRuleType(),
                evaluation.groupKey(),
                aggregateResult,
                evaluation.sequence(),
                actionDecision.outcome(),
                actionDecision.suppressionReason(),
                actionDecision.deliveryId()));
        }

        boolean matched = !matchedRuleNames.isEmpty();
        eventRuleOutcomeRepository.saveAll(outcomes);
        event.recordProcessingTrace(
            matchedRuleNames.size(), queuedRuleNames.size(), suppressedRuleNames.size());
        if (eventTimeDecision != null) {
            recordEventTimeMetric(eventTimeDecision.status());
        }
        acceptedEvents.increment();
        if (matched) {
            matchedEvents.increment();
        }
        return new ProcessingResult(
            event,
            new EventResponse(
                request,
                "accepted",
                matched,
                matchedRuleNames,
                queuedRuleNames,
                aggregates,
                sequences,
                suppressedRuleNames,
                eventTimeDecision != null ? eventTimeDecision.status() : null));
    }

    private void recordEventTimeMetric(EventTimeStatus status) {
        switch (status) {
            case ON_TIME -> onTimeEvents.increment();
            case LATE_ACCEPTED -> lateAcceptedEvents.increment();
            case TOO_LATE -> tooLateEvents.increment();
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return normalized;
    }

    private Map<String, Object> replayResponse(EventIdempotency idempotency) {
        if (idempotency.getResponsePayload() == null) {
            throw new IllegalStateException("Completed idempotency record has no response");
        }
        return idempotency.getResponsePayload();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toPayload(Object response) {
        try {
            // Store the idempotent response as JSON-compatible values. In
            // particular, sequence details contain Instant timestamps and the
            // Hibernate JSON mapper does not provide a Java-time module.
            return objectMapper.readValue(objectMapper.writeValueAsString(response), Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize event response", exception);
        }
    }

    private EventIngestionResult project(EventResponse response, ApiKey apiKey, boolean replayed) {
        Object responseBody = apiKey.getEffectiveResponseMode() == ResponseMode.COMPACT
            ? CompactEventResponse.from(response)
            : response;
        return new EventIngestionResult(responseBody, replayed, response);
    }

    private record ProcessingResult(Event event, EventResponse response) {
    }

    private ActionDecision enqueueWebhook(Rule rule, Event event, EventRequest request,
                                          AggregateResult aggregateResult,
                                          SequenceResult sequenceResult,
                                          String groupKey) {
        Instant occurredAt = event.getOccurredAt();
        var state = needsActionState(rule) ? webhookCooldownService.lockState(rule, groupKey) : null;
        RuleActionWindow windowState = isOncePerWindowWebhook(rule)
            ? webhookCooldownService.lockWindow(rule, groupKey, windowStart(occurredAt, rule.getWindowSeconds()))
            : null;

        if (isEdgeWebhook(rule)) {
            if (webhookCooldownService.isEdgeSuppressed(state)) {
                return new ActionDecision(
                    EventRuleActionOutcome.WEBHOOK_SUPPRESSED,
                    "EDGE_ALREADY_MATCHED",
                    null);
            }
        }

        if (windowState != null
            && (webhookCooldownService.isWindowDelivered(windowState)
                || windowState.getPendingOutboxId() != null)) {
            return new ActionDecision(
                EventRuleActionOutcome.WEBHOOK_SUPPRESSED,
                "WINDOW_ALREADY_RESERVED_OR_DELIVERED",
                null);
        }

        if (rule.getCooldownSeconds() != null && rule.getCooldownSeconds() > 0) {
            // needsActionState(...) guarantees that state is present for cooldown rules.
            Instant now = Instant.now();
            if (state.getPendingOutboxId() != null
                || webhookCooldownService.isSuppressed(state, rule.getCooldownSeconds(), now)) {
                return new ActionDecision(
                    EventRuleActionOutcome.WEBHOOK_SUPPRESSED,
                    "COOLDOWN_ACTIVE_OR_RESERVED",
                    null);
            }
        }

        var enqueueResult = webhookOutboxService.enqueue(
            rule,
            event,
            request,
            aggregateResult,
            sequenceResult,
            groupKey,
            windowState != null ? windowState.getWindowStart() : null);
        Long outboxId = enqueueResult.outbox().getId();

        if (state != null) {
            if ((rule.getCooldownSeconds() != null && rule.getCooldownSeconds() > 0)
                || isEdgeWebhook(rule)) {
                state.setPendingOutboxId(outboxId);
            }
            if (isEdgeWebhook(rule)) {
                // Reserve the rising edge when queued; delivery success is handled by the worker.
                state.setLastMatched(true);
            }
        }
        if (windowState != null) {
            windowState.setPendingOutboxId(outboxId);
        }

        return new ActionDecision(EventRuleActionOutcome.WEBHOOK_QUEUED, null, outboxId);
    }

    private boolean isEdgeWebhook(Rule rule) {
        return rule.getAction() == RuleAction.WEBHOOK
            && rule.getCallbackUrl() != null
            && rule.getEffectiveTriggerMode() == TriggerMode.EDGE;
    }

    private boolean isOncePerWindowWebhook(Rule rule) {
        return rule.getAction() == RuleAction.WEBHOOK
            && rule.getCallbackUrl() != null
            && rule.getEffectiveTriggerMode() == TriggerMode.ONCE_PER_WINDOW
            && rule.getRuleType() == RuleType.AGGREGATE
            && rule.getWindowSeconds() != null
            && rule.getWindowSeconds() > 0;
    }

    private boolean needsActionState(Rule rule) {
        return rule.getAction() == RuleAction.WEBHOOK
            && rule.getCallbackUrl() != null
            && (isEdgeWebhook(rule) || (rule.getCooldownSeconds() != null && rule.getCooldownSeconds() > 0));
    }

    private Instant windowStart(Instant occurredAt, Integer windowSeconds) {
        long bucket = Math.floorDiv(occurredAt.getEpochSecond(), windowSeconds);
        return Instant.ofEpochSecond(bucket * windowSeconds);
    }

    private boolean hasTriggerScope(Event event, Rule rule, RuleEvaluation evaluation) {
        if (rule.getRuleType() == RuleType.SEQUENCE) {
            boolean routed = rule.getSequenceSteps().stream()
                .anyMatch(step -> step.getEventType().equals(event.getType())
                    && step.getSource().equals(event.getSource()));
            return routed && (!usesGrouping(rule) || evaluation.groupKey() != null);
        }
        return rule.getRuleType() != RuleType.AGGREGATE
            || rule.getGroupBy() == null
            || rule.getGroupBy().isBlank()
            || evaluation.groupKey() != null;
    }

    private boolean usesGrouping(Rule rule) {
        return rule.getGroupBy() != null && !rule.getGroupBy().isBlank();
    }

    private void resetEdgeState(Rule rule, String groupKey) {
        var state = webhookCooldownService.lockState(rule, groupKey);
        webhookCooldownService.resetEdgeState(state);
    }


    private record ActionDecision(EventRuleActionOutcome outcome, String suppressionReason,
                                  Long deliveryId) {
    }
}
