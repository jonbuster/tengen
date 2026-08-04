package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.EventTimeStatus;

import java.util.List;

/**
 * Producer-facing summary for API keys configured with the compact response
 * mode. Processing and persistence still use the complete event response.
 */
public record CompactEventResponse(
        String status,
        boolean matched,
        List<String> rules,
        List<String> queuedRules,
        List<String> suppressedRules,
        EventTimeStatus eventTimeStatus) {

    public CompactEventResponse {
        rules = rules != null ? List.copyOf(rules) : List.of();
        queuedRules = queuedRules != null ? List.copyOf(queuedRules) : List.of();
        suppressedRules = suppressedRules != null ? List.copyOf(suppressedRules) : List.of();
    }

    public static CompactEventResponse from(EventResponse response) {
        return new CompactEventResponse(
            response.status(),
            response.matched(),
            response.rules(),
            response.queuedRules(),
            response.suppressedRules(),
            response.eventTimeStatus());
    }
}
