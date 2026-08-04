package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.AggregateResult;
import com.tengencorp.tengen.dto.AbsenceResult;
import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.dto.SequenceResult;
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
                                 AggregateResult aggregateResult, SequenceResult sequenceResult,
                                 String groupKey,
                                 Instant windowStart) {
        String scopeKey = groupKey != null ? groupKey : GLOBAL_SCOPE;
        TriggerMode triggerMode = rule.getEffectiveTriggerMode();
        String deduplicationKey = deduplicationKey(rule, event, scopeKey, triggerMode, windowStart);

        var existing = outboxRepository.findByDeduplicationKey(deduplicationKey);
        if (existing.isPresent()) {
            return new EnqueueResult(existing.get(), false);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", jsonMap(request));
        payload.put("status", "accepted");
        payload.put("matched", true);
        payload.put("rules", List.of(rule.getName()));
        Map<String, Object> aggregates = new LinkedHashMap<>();
        if (aggregateResult != null) {
            aggregates.put(rule.getName(), aggregateResult);
        }
        payload.put("aggregates", aggregates);
        Map<String, Object> sequences = new LinkedHashMap<>();
        if (sequenceResult != null) {
            // Persist a JSON-compatible map. Hibernate's JSON mapper does not
            // register a Java-time module, while sequence details contain Instants.
            sequences.put(rule.getName(), jsonMap(sequenceResult));
        }
        payload.put("sequences", sequences);

        WebhookOutbox outbox = new WebhookOutbox(
            event,
            rule.getId(),
            rule.getName(),
            rule.getCallbackUrl(),
            payload,
            scopeKey,
            triggerMode,
            windowStart,
            rule.getEffectiveRevision(),
            rule.getCooldownSeconds(),
            deduplicationKey);
        return new EnqueueResult(outboxRepository.save(outbox), true);
    }

    /** Enqueue the delayed action for one triggered absence instance. */
    public EnqueueResult enqueueAbsence(Rule rule, Event event, AbsenceResult absence) {
        String scopeKey = absence.groupKey() != null ? absence.groupKey() : GLOBAL_SCOPE;
        String deduplicationKey = "ABSENCE:rule=" + rule.getId()
            + ":revision=" + rule.getEffectiveRevision()
            + ":instance=" + absence.instanceId();

        var existing = outboxRepository.findByDeduplicationKey(deduplicationKey);
        if (existing.isPresent()) {
            return new EnqueueResult(existing.get(), false);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventPayload(event));
        payload.put("status", "accepted");
        payload.put("matched", true);
        payload.put("rules", List.of(rule.getName()));
        payload.put("aggregates", Map.of());
        payload.put("sequences", Map.of());
        payload.put("absences", Map.of(rule.getName(), absencePayload(absence)));

        WebhookOutbox outbox = new WebhookOutbox(
            event,
            rule.getId(),
            rule.getName(),
            rule.getCallbackUrl(),
            payload,
            scopeKey,
            TriggerMode.EVERY_MATCH,
            null,
            rule.getEffectiveRevision(),
            rule.getCooldownSeconds(),
            deduplicationKey);
        return new EnqueueResult(outboxRepository.save(outbox), true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(Object value) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(value), Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize webhook payload", exception);
        }
    }

    private Map<String, Object> eventPayload(Event event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", event.getType());
        result.put("source", event.getSource());
        result.put("timestamp", event.getOccurredAt());
        result.put("data", event.getData());
        return result;
    }

    private Map<String, Object> absencePayload(AbsenceResult absence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", absence.instanceId());
        result.put("groupKey", absence.groupKey());
        result.put("startEventId", absence.startEventId());
        result.put("startOccurredAt",
            absence.startOccurredAt() != null ? absence.startOccurredAt().toString() : null);
        result.put("expectedEventType", absence.expectedEventType());
        result.put("expectedSource", absence.expectedSource());
        result.put("deadlineAt",
            absence.deadlineAt() != null ? absence.deadlineAt().toString() : null);
        result.put("triggeringWatermark",
            absence.triggeringWatermark() != null ? absence.triggeringWatermark().toString() : null);
        return result;
    }

    private String deduplicationKey(Rule rule, Event event, String scopeKey,
                                    TriggerMode triggerMode, Instant windowStart) {
        String encodedScope = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(scopeKey.getBytes(StandardCharsets.UTF_8));
        return switch (triggerMode) {
            case EVERY_MATCH -> "EVERY_MATCH:rule=" + rule.getId() + ":revision="
                + rule.getEffectiveRevision() + ":event=" + event.getId();
            case EDGE -> "EDGE:rule=" + rule.getId() + ":revision=" + rule.getEffectiveRevision()
                + ":event=" + event.getId()
                + ":scope=" + encodedScope;
            case ONCE_PER_WINDOW -> "ONCE_PER_WINDOW:rule=" + rule.getId()
                + ":revision=" + rule.getEffectiveRevision()
                + ":window=" + (windowStart != null ? windowStart.getEpochSecond() : "none")
                + ":scope=" + encodedScope;
        };
    }

    public record EnqueueResult(WebhookOutbox outbox, boolean created) {
    }
}
