package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.AggregateType;

/**
 * Windowed aggregate evaluation result returned in the API response.
 */
public record AggregateResult(
        String ruleType,
        String function,
        double value,
        double threshold,
        int windowSeconds,
        String groupKey) {
}
