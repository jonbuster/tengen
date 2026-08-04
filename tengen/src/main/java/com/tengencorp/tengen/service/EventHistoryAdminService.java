package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.EventHistoryDetail;
import com.tengencorp.tengen.dto.EventHistoryPage;
import com.tengencorp.tengen.dto.EventHistorySummary;
import com.tengencorp.tengen.dto.EventRuleOutcomeResponse;
import com.tengencorp.tengen.dto.AbsenceInstanceResponse;
import com.tengencorp.tengen.dto.WebhookDeliverySummary;
import com.tengencorp.tengen.dto.RabbitMqBrokerMetadata;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.IngestionOrigin;
import com.tengencorp.tengen.entity.EventTimeStatus;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.repository.EventRepository;
import com.tengencorp.tengen.repository.EventRuleOutcomeRepository;
import com.tengencorp.tengen.repository.RuleAbsenceInstanceRepository;
import com.tengencorp.tengen.repository.WebhookOutboxRepository;
import com.tengencorp.tengen.repository.RabbitMqMessageReceiptRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Read-only admin queries for Event Explorer. */
@Service
public class EventHistoryAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final EventRepository eventRepository;
    private final EventRuleOutcomeRepository outcomeRepository;
    private final WebhookOutboxRepository outboxRepository;
    private final RuleAbsenceInstanceRepository absenceInstanceRepository;
    private final RabbitMqMessageReceiptRepository rabbitMqMessageReceiptRepository;
    private final ObjectMapper objectMapper;

    public EventHistoryAdminService(EventRepository eventRepository,
                                    EventRuleOutcomeRepository outcomeRepository,
                                    WebhookOutboxRepository outboxRepository,
                                    RuleAbsenceInstanceRepository absenceInstanceRepository,
                                    RabbitMqMessageReceiptRepository rabbitMqMessageReceiptRepository,
                                    ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.outcomeRepository = outcomeRepository;
        this.outboxRepository = outboxRepository;
        this.absenceInstanceRepository = absenceInstanceRepository;
        this.rabbitMqMessageReceiptRepository = rabbitMqMessageReceiptRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public EventHistoryPage list(int page, int size, Long eventId, String type, String source,
                                 Long apiKeyId, Boolean matched, Boolean traceAvailable,
                                 EventTimeStatus eventTimeStatus, Instant from, Instant to) {
        return list(page, size, eventId, type, source, apiKeyId, matched, traceAvailable,
            eventTimeStatus, null, from, to);
    }

    @Transactional(readOnly = true)
    public EventHistoryPage list(int page, int size, Long eventId, String type, String source,
                                 Long apiKeyId, Boolean matched, Boolean traceAvailable,
                                 EventTimeStatus eventTimeStatus, IngestionOrigin ingestionOrigin,
                                 Instant from, Instant to) {
        validatePage(page, size);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be earlier than to");
        }
        if (eventId != null && eventId < 1) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        if (apiKeyId != null && apiKeyId < 1) {
            throw new IllegalArgumentException("apiKeyId must be positive");
        }

        Specification<Event> specification = (root, query, builder) -> builder.conjunction();
        if (eventId != null) {
            specification = specification.and((root, query, builder) ->
                builder.equal(root.get("id"), eventId));
        }
        String normalizedType = normalize(type);
        if (normalizedType != null) {
            specification = specification.and((root, query, builder) ->
                builder.equal(root.get("type"), normalizedType));
        }
        String normalizedSource = normalize(source);
        if (normalizedSource != null) {
            specification = specification.and((root, query, builder) ->
                builder.equal(root.get("source"), normalizedSource));
        }
        if (apiKeyId != null) {
            specification = specification.and((root, query, builder) ->
                builder.equal(root.get("apiKey").get("id"), apiKeyId));
        }
        if (traceAvailable != null) {
            specification = specification.and((root, query, builder) -> traceAvailable
                ? builder.isNotNull(root.get("processingTraceVersion"))
                : builder.isNull(root.get("processingTraceVersion")));
        }
        if (matched != null) {
            specification = specification.and((root, query, builder) -> {
                var traced = builder.isNotNull(root.get("processingTraceVersion"));
                var count = root.<Integer>get("matchedRuleCount");
                return matched
                    ? builder.and(traced, builder.greaterThan(count, 0))
                    : builder.and(traced, builder.equal(count, 0));
            });
        }
        if (eventTimeStatus != null) {
            specification = specification.and((root, query, builder) ->
                builder.equal(root.get("eventTimeStatus"), eventTimeStatus));
        }
        if (ingestionOrigin != null) {
            specification = specification.and((root, query, builder) ->
                builder.equal(root.get("ingestionOrigin"), ingestionOrigin));
        }
        if (from != null) {
            specification = specification.and((root, query, builder) ->
                builder.greaterThanOrEqualTo(root.get("receivedAt"), from));
        }
        if (to != null) {
            specification = specification.and((root, query, builder) ->
                builder.lessThan(root.get("receivedAt"), to));
        }

        var pageable = PageRequest.of(page, size,
            Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id")));
        Page<Event> results = eventRepository.findAll(specification, pageable);
        return new EventHistoryPage(
            results.map(EventHistorySummary::from).getContent(),
            results.getNumber(),
            results.getSize(),
            results.getTotalElements(),
            results.getTotalPages());
    }

    @Transactional(readOnly = true)
    public EventHistoryDetail get(Long id) {
        Event event = eventRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Event " + id + " not found"));
        List<EventRuleOutcomeResponse> outcomes = outcomeRepository.findByEventIdOrderByIdAsc(id)
            .stream()
            .map(outcome -> EventRuleOutcomeResponse.from(outcome, objectMapper))
            .toList();
        List<WebhookDeliverySummary> deliveries = outboxRepository
            .findByEvent_IdOrderByCreatedAtDescIdDesc(id)
            .stream()
            .map(outbox -> WebhookDeliverySummary.from(
                outbox, WebhookDeliveryAdminService.safeDestination(outbox.getCallbackUrl())))
            .toList();
        var absenceById = new LinkedHashMap<Long, AbsenceInstanceResponse>();
        absenceInstanceRepository.findByStartEvent_IdOrderByCreatedAtAscIdAsc(id)
            .forEach(instance -> absenceById.put(instance.getId(), AbsenceInstanceResponse.from(instance)));
        absenceInstanceRepository.findByResolvedByEvent_IdOrderByCreatedAtAscIdAsc(id)
            .forEach(instance -> absenceById.put(instance.getId(), AbsenceInstanceResponse.from(instance)));
        List<AbsenceInstanceResponse> absenceInstances = new ArrayList<>(absenceById.values());
        RabbitMqBrokerMetadata rabbitMqMetadata = rabbitMqMessageReceiptRepository
            .findFirstByEvent_IdOrderByProcessedAtDescIdDesc(id)
            .map(receipt -> {
                var connector = receipt.getConnector();
                return new RabbitMqBrokerMetadata(
                    connector != null ? connector.getId() : null,
                    connector != null ? connector.getConnectorKey() : null,
                    connector != null ? connector.getDisplayName() : null,
                    receipt.getQueueName(),
                    receipt.getSourceExchange(),
                    receipt.getRoutingKey(),
                    receipt.getMessageId(),
                    receipt.getProcessedAt());
            })
            .orElse(null);
        return EventHistoryDetail.from(event, outcomes, deliveries, absenceInstances, rabbitMqMetadata);
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be non-negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
