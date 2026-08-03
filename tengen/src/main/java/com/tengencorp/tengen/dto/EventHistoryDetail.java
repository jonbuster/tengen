package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.Event;

import java.util.List;
import java.util.Map;

/** Full admin view of an event and its persisted processing trace. */
public record EventHistoryDetail(
        EventHistorySummary event,
        Map<String, Object> data,
        List<EventRuleOutcomeResponse> rules,
        List<WebhookDeliverySummary> deliveries) {

    public static EventHistoryDetail from(Event event,
                                          List<EventRuleOutcomeResponse> rules,
                                          List<WebhookDeliverySummary> deliveries) {
        return new EventHistoryDetail(EventHistorySummary.from(event), event.getData(), rules, deliveries);
    }
}
