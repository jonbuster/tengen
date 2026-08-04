package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.Event;

import java.util.List;
import java.util.Map;

/** Full admin view of an event and its persisted processing trace. */
public record EventHistoryDetail(
        EventHistorySummary event,
        Map<String, Object> data,
        List<EventRuleOutcomeResponse> rules,
        List<WebhookDeliverySummary> deliveries,
        List<AbsenceInstanceResponse> absenceInstances,
        RabbitMqBrokerMetadata rabbitMqMetadata) {

    public EventHistoryDetail(EventHistorySummary event, Map<String, Object> data,
                              List<EventRuleOutcomeResponse> rules,
                              List<WebhookDeliverySummary> deliveries,
                              List<AbsenceInstanceResponse> absenceInstances) {
        this(event, data, rules, deliveries, absenceInstances, null);
    }

    public static EventHistoryDetail from(Event event,
                                          List<EventRuleOutcomeResponse> rules,
                                          List<WebhookDeliverySummary> deliveries,
                                          List<AbsenceInstanceResponse> absenceInstances,
                                          RabbitMqBrokerMetadata rabbitMqMetadata) {
        return new EventHistoryDetail(
            EventHistorySummary.from(event), event.getData(), rules, deliveries, absenceInstances,
            rabbitMqMetadata);
    }

    public static EventHistoryDetail from(Event event,
                                          List<EventRuleOutcomeResponse> rules,
                                          List<WebhookDeliverySummary> deliveries,
                                          List<AbsenceInstanceResponse> absenceInstances) {
        return from(event, rules, deliveries, absenceInstances, null);
    }
}
