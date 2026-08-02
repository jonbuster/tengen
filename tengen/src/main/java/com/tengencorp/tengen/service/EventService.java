package com.tengencorp.tengen.service;

import com.tengencorp.tengen.repository.ApiKeyRepository;
import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.dto.EventResponse;
import com.tengencorp.tengen.entity.Event;
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
import com.tengencorp.tengen.entity.EventIdempotency;
import com.tengencorp.tengen.entity.EventIdempotencyStatus;
import com.tengencorp.tengen.exception.IdempotencyConflictException;
import com.tengencorp.tengen.helper.EventRequestHasher;
import com.tengencorp.tengen.repository.EventIdempotencyRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

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
    private final WebhookOutboxService webhookOutboxService;
    private final WebhookCooldownService webhookCooldownService;
    private final ApiKeyService apiKeyService;
    private final EventIdempotencyRepository eventIdempotencyRepository;
    private final EventRequestHasher eventRequestHasher;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository eventRepository, ApiKeyRepository apiKeyRepository,
                        RuleRepository ruleRepository, RuleEngine ruleEngine,
                        WebhookOutboxService webhookOutboxService,
                        WebhookCooldownService webhookCooldownService, ApiKeyService apiKeyService,
                        EventIdempotencyRepository eventIdempotencyRepository,
                        EventRequestHasher eventRequestHasher, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.ruleRepository = ruleRepository;
        this.ruleEngine = ruleEngine;
        this.webhookOutboxService = webhookOutboxService;
        this.webhookCooldownService = webhookCooldownService;
        this.apiKeyService = apiKeyService;
        this.eventIdempotencyRepository = eventIdempotencyRepository;
        this.eventRequestHasher = eventRequestHasher;
        this.objectMapper = objectMapper;
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
        if (apiKeyId == null) {
            throw new AccessDeniedException("API key is required");
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
            return processEvent(request, apiKey).response();
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
                return replayResponse(idempotency);
            }
            throw new IdempotencyConflictException(
                "The request for this Idempotency-Key is still being processed; retry later");
        }

        ProcessingResult result = processEvent(request, apiKey);
        idempotency.setEvent(result.event());
        idempotency.setResponsePayload(toPayload(result.response()));
        idempotency.setStatus(EventIdempotencyStatus.COMPLETED);
        idempotency.setCompletedAt(Instant.now());
        return result.response();
    }

    private ProcessingResult processEvent(EventRequest request, ApiKey apiKey) {
        Instant occurredAt = request.timestamp() != null ? request.timestamp() : Instant.now();
        Event event = new Event(request.type(), request.source(), occurredAt, request.data(),
            apiKey);
        event = eventRepository.save(event);

        List<Rule> activeRules = ruleRepository.findByActiveTrueAndArchivedAtIsNullOrderByNameAsc();
        List<String> matchedRuleNames = new ArrayList<>();
        List<String> queuedRuleNames = new ArrayList<>();
        List<String> suppressedRuleNames = new ArrayList<>();
        Map<String, AggregateResult> aggregates = new LinkedHashMap<>();

        for (Rule rule : activeRules) {
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

            if (rule.getAction() == RuleAction.WEBHOOK && rule.getCallbackUrl() != null) {
                DeliveryDecision decision = enqueueWebhook(
                    rule, event, request, aggregateResult, evaluation.groupKey());
                if (decision == DeliveryDecision.QUEUED) {
                    queuedRuleNames.add(rule.getName());
                } else if (decision == DeliveryDecision.SUPPRESSED) {
                    suppressedRuleNames.add(rule.getName());
                }
            }
        }

        boolean matched = !matchedRuleNames.isEmpty();
        return new ProcessingResult(
            event,
            new EventResponse(
                request,
                "accepted",
                matched,
                matchedRuleNames,
                queuedRuleNames,
                aggregates,
                suppressedRuleNames));
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

    private EventResponse replayResponse(EventIdempotency idempotency) {
        if (idempotency.getResponsePayload() == null) {
            throw new IllegalStateException("Completed idempotency record has no response");
        }
        return objectMapper.convertValue(idempotency.getResponsePayload(), EventResponse.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toPayload(EventResponse response) {
        return objectMapper.convertValue(response, Map.class);
    }

    private record ProcessingResult(Event event, EventResponse response) {
    }

    private DeliveryDecision enqueueWebhook(Rule rule, Event event, EventRequest request,
                                             AggregateResult aggregateResult, String groupKey) {
        Instant occurredAt = event.getOccurredAt();
        var state = needsActionState(rule) ? webhookCooldownService.lockState(rule, groupKey) : null;
        RuleActionWindow windowState = isOncePerWindowWebhook(rule)
            ? webhookCooldownService.lockWindow(rule, groupKey, windowStart(occurredAt, rule.getWindowSeconds()))
            : null;

        if (isEdgeWebhook(rule)) {
            if (webhookCooldownService.isEdgeSuppressed(state)) {
                return DeliveryDecision.SUPPRESSED;
            }
        }

        if (windowState != null
            && (webhookCooldownService.isWindowDelivered(windowState)
                || windowState.getPendingOutboxId() != null)) {
            return DeliveryDecision.SUPPRESSED;
        }

        if (rule.getCooldownSeconds() != null && rule.getCooldownSeconds() > 0) {
            // needsActionState(...) guarantees that state is present for cooldown rules.
            Instant now = Instant.now();
            if (state.getPendingOutboxId() != null
                || webhookCooldownService.isSuppressed(state, rule.getCooldownSeconds(), now)) {
                return DeliveryDecision.SUPPRESSED;
            }
        }

        var enqueueResult = webhookOutboxService.enqueue(
            rule,
            event,
            request,
            aggregateResult,
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

        return DeliveryDecision.QUEUED;
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
        if (!rule.getEventType().equals(event.getType()) || !rule.getSource().equals(event.getSource())) {
            return false;
        }
        return rule.getRuleType() != RuleType.AGGREGATE
            || rule.getGroupBy() == null
            || rule.getGroupBy().isBlank()
            || evaluation.groupKey() != null;
    }

    private void resetEdgeState(Rule rule, String groupKey) {
        var state = webhookCooldownService.lockState(rule, groupKey);
        webhookCooldownService.resetEdgeState(state);
    }


    private enum DeliveryDecision {
        QUEUED,
        SUPPRESSED
    }
}
