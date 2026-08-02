package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.AggregateResult;
import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.TriggerMode;
import com.tengencorp.tengen.entity.WebhookOutbox;
import com.tengencorp.tengen.repository.WebhookOutboxRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates durable webhook delivery intents without making outbound HTTP calls. */
@Service
public class WebhookOutboxService {

    private static final String GLOBAL_SCOPE = "";

    private final WebhookOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public WebhookOutboxService(WebhookOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueue one logical webhook action. Trigger eligibility is decided by the
     * caller while holding the relevant action-state lock.
     */
    public EnqueueResult enqueue(Rule rule, Event event, EventRequest request,
                                 AggregateResult aggregateResult, String groupKey,
                                 Instant windowStart) {
        String scopeKey = groupKey != null ? groupKey : GLOBAL_SCOPE;
        TriggerMode triggerMode = rule.getEffectiveTriggerMode();
        String deduplicationKey = deduplicationKey(rule, event, scopeKey, triggerMode, windowStart);

        var existing = outboxRepository.findByDeduplicationKey(deduplicationKey);
        if (existing.isPresent()) {
            return new EnqueueResult(existing.get(), false);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", objectMapper.convertValue(request, Map.class));
        payload.put("status", "accepted");
        payload.put("matched", true);
        payload.put("rules", List.of(rule.getName()));
        Map<String, Object> aggregates = new LinkedHashMap<>();
        if (aggregateResult != null) {
            aggregates.put(rule.getName(), aggregateResult);
        }
        payload.put("aggregates", aggregates);

        WebhookOutbox outbox = new WebhookOutbox(
            event,
            rule.getId(),
            rule.getName(),
            rule.getCallbackUrl(),
            payload,
            scopeKey,
            triggerMode,
            windowStart,
            rule.getCooldownSeconds(),
            deduplicationKey);
        return new EnqueueResult(outboxRepository.save(outbox), true);
    }

    private String deduplicationKey(Rule rule, Event event, String scopeKey,
                                    TriggerMode triggerMode, Instant windowStart) {
        String encodedScope = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(scopeKey.getBytes(StandardCharsets.UTF_8));
        return switch (triggerMode) {
            case EVERY_MATCH -> "EVERY_MATCH:rule=" + rule.getId() + ":event=" + event.getId();
            case EDGE -> "EDGE:rule=" + rule.getId() + ":event=" + event.getId()
                + ":scope=" + encodedScope;
            case ONCE_PER_WINDOW -> "ONCE_PER_WINDOW:rule=" + rule.getId()
                + ":window=" + (windowStart != null ? windowStart.getEpochSecond() : "none")
                + ":scope=" + encodedScope;
        };
    }

    public record EnqueueResult(WebhookOutbox outbox, boolean created) {
    }
}
