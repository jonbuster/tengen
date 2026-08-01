package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.dto.AggregateResult;

import java.util.List;
import java.util.Map;

/**
 * Response body for POST /api/events.
 *
 * @param event      the original request, echoed back
 * @param status     always "accepted"
 * @param matched    whether at least one rule matched
 * @param rules      names of matched rules
 * @param aggregates per-rule windowed aggregate results (AGGREGATE matches only)
 */
public record EventResponse(
        Object event,
        String status,
        boolean matched,
        List<String> rules,
        Map<String, AggregateResult> aggregates) {

    public static EventResponse noMatch(Object event) {
        return new EventResponse(event, "accepted", false, List.of(), Map.of());
    }
}
