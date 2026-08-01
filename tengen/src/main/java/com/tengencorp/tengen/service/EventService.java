package com.tengencorp.tengen.service;

import com.tengencorp.tengen.repository.ApiKeyRepository;
import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.dto.EventResponse;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.repository.EventRepository;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.dto.AggregateResult;
import com.tengencorp.tengen.service.RuleEngine;
import com.tengencorp.tengen.dto.RuleEvaluation;
import com.tengencorp.tengen.service.WebhookClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final RuleRepository ruleRepository;
    private final RuleEngine ruleEngine;
    private final WebhookClient webhookClient;

    public EventService(EventRepository eventRepository, ApiKeyRepository apiKeyRepository,
                        RuleRepository ruleRepository, RuleEngine ruleEngine, WebhookClient webhookClient) {
        this.eventRepository = eventRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.ruleRepository = ruleRepository;
        this.ruleEngine = ruleEngine;
        this.webhookClient = webhookClient;
    }

    @Transactional
    public EventResponse process(EventRequest request) {
        return process(request, null);
    }

    @Transactional
    public EventResponse process(EventRequest request, Long apiKeyId) {
        Instant occurredAt = request.timestamp() != null ? request.timestamp() : Instant.now();
        Event event = new Event(request.type(), request.source(), occurredAt, request.data(),
            apiKeyId != null ? apiKeyRepository.getReferenceById(apiKeyId) : null);
        event = eventRepository.save(event);

        List<Rule> activeRules = ruleRepository.findByActiveTrueOrderByNameAsc();
        List<String> matchedRuleNames = new ArrayList<>();
        Map<String, AggregateResult> aggregates = new LinkedHashMap<>();

        for (Rule rule : activeRules) {
            RuleEvaluation evaluation = ruleEngine.evaluate(event, rule);
            if (!evaluation.matched(rule)) {
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
                    rule.getWindowSeconds() != null ? rule.getWindowSeconds() : 0
                );
                aggregates.put(rule.getName(), aggregateResult);
            }

            if (rule.getAction() == RuleAction.WEBHOOK && rule.getCallbackUrl() != null) {
                dispatchWebhook(rule, request, aggregateResult);
            }
        }

        boolean matched = !matchedRuleNames.isEmpty();
        return new EventResponse(request, "accepted", matched, matchedRuleNames, aggregates);
    }

    private void dispatchWebhook(Rule rule, EventRequest request, AggregateResult aggregateResult) {
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

        boolean delivered = webhookClient.deliver(rule.getCallbackUrl(), payload);
        if (!delivered) {
            log.warn("Webhook for rule [{}] could not be delivered to {}", rule.getName(), rule.getCallbackUrl());
        }
    }
}
