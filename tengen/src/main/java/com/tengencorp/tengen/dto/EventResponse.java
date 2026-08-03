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
 * @param queuedRules matched webhook rules whose delivery intent was persisted
 * @param aggregates per-rule windowed aggregate results (AGGREGATE matches only)
 * @param sequences completed sequence details (SEQUENCE matches only)
 * @param suppressedRules matched webhook rules whose delivery was suppressed by cooldown
 */
public record EventResponse(
        Object event,
        String status,
        boolean matched,
        List<String> rules,
        List<String> queuedRules,
        Map<String, AggregateResult> aggregates,
        Map<String, SequenceResult> sequences,
        List<String> suppressedRules) {

    public EventResponse {
        rules = rules != null ? List.copyOf(rules) : List.of();
        queuedRules = queuedRules != null ? List.copyOf(queuedRules) : List.of();
        aggregates = aggregates != null ? Map.copyOf(aggregates) : Map.of();
        sequences = sequences != null ? Map.copyOf(sequences) : Map.of();
        suppressedRules = suppressedRules != null ? List.copyOf(suppressedRules) : List.of();
    }

    /** Backward-compatible constructor for callers using the pre-outbox shape. */
    public EventResponse(Object event, String status, boolean matched, List<String> rules,
                         Map<String, AggregateResult> aggregates,
                         List<String> suppressedRules) {
        this(event, status, matched, rules, List.of(), aggregates, Map.of(), suppressedRules);
    }

    public static EventResponse noMatch(Object event) {
        return new EventResponse(event, "accepted", false, List.of(), List.of(), Map.of(), Map.of(), List.of());
    }
}
