package com.tengencorp.tengen.service;

import com.tengencorp.tengen.repository.ApiKeyRepository;
import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.dto.EventResponse;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.repository.EventRepository;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * active rule, dispatch webhooks for matches, and build the API response.
 */
@Service
public class EventService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final RuleRepository ruleRepository;
    private final RuleEngine ruleEngine;
    private final WebhookClient webhookClient;
    private final WebhookCooldownService webhookCooldownService;
    private final ApiKeyService apiKeyService;
    private final EventIdempotencyRepository eventIdempotencyRepository;
    private final EventRequestHasher eventRequestHasher;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository eventRepository, ApiKeyRepository apiKeyRepository,
                        RuleRepository ruleRepository, RuleEngine ruleEngine, WebhookClient webhookClient,
                        WebhookCooldownService webhookCooldownService, ApiKeyService apiKeyService,
                        EventIdempotencyRepository eventIdempotencyRepository,
                        EventRequestHasher eventRequestHasher, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.ruleRepository = ruleRepository;
        this.ruleEngine = ruleEngine;
        this.webhookClient = webhookClient;
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

        List<Rule> activeRules = ruleRepository.findByActiveTrueOrderByNameAsc();
        List<String> matchedRuleNames = new ArrayList<>();
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
                if (dispatchWebhook(rule, request, aggregateResult, evaluation.groupKey())) {
                    suppressedRuleNames.add(rule.getName());
                }
            }
        }

        boolean matched = !matchedRuleNames.isEmpty();
        return new ProcessingResult(
            event,
            new EventResponse(request, "accepted", matched, matchedRuleNames, aggregates, suppressedRuleNames));
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

    private boolean dispatchWebhook(Rule rule, EventRequest request, AggregateResult aggregateResult, String groupKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", request);
        payload.put("status", "accepted");
        payload.put("matched", true);
        payload.put("rules", List.of(rule.getName()));
        Map<String, Object> aggMap = new LinkedHashMap<>();
        if (aggregateResult != null) {
            aggMap.put(rule.getName(), aggregateResult);
        }
        payload.put("aggregates", aggMap);

        var state = needsActionState(rule) ? webhookCooldownService.lockState(rule, groupKey) : null;

        if (isEdgeWebhook(rule)) {
            if (webhookCooldownService.isEdgeSuppressed(state)) {
                return false;
            }
        }

        if (rule.getCooldownSeconds() != null && rule.getCooldownSeconds() > 0) {
            if (state == null) {
                state = webhookCooldownService.lockState(rule, groupKey);
            }
            Instant now = Instant.now();
            if (webhookCooldownService.isSuppressed(state, rule.getCooldownSeconds(), now)) {
                return true;
            }

            boolean delivered = deliverWebhook(rule, payload);
            if (delivered) {
                webhookCooldownService.recordSuccessfulDelivery(state, Instant.now());
                if (isEdgeWebhook(rule)) {
                    webhookCooldownService.recordSuccessfulEdgeDelivery(state);
                }
            }
            return false;
        }

        boolean delivered = deliverWebhook(rule, payload);
        if (delivered && isEdgeWebhook(rule)) {
            webhookCooldownService.recordSuccessfulEdgeDelivery(state);
        }
        return false;
    }

    private boolean isEdgeWebhook(Rule rule) {
        return rule.getAction() == RuleAction.WEBHOOK
            && rule.getCallbackUrl() != null
            && rule.getEffectiveTriggerMode() == TriggerMode.EDGE;
    }

    private boolean needsActionState(Rule rule) {
        return rule.getAction() == RuleAction.WEBHOOK
            && rule.getCallbackUrl() != null
            && (isEdgeWebhook(rule) || (rule.getCooldownSeconds() != null && rule.getCooldownSeconds() > 0));
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

    private boolean deliverWebhook(Rule rule, Map<String, Object> payload) {
        boolean delivered = webhookClient.deliver(rule.getCallbackUrl(), payload);
        if (!delivered) {
            log.warn("Webhook for rule [{}] could not be delivered to {}", rule.getName(), rule.getCallbackUrl());
        }
        return delivered;
    }
}
