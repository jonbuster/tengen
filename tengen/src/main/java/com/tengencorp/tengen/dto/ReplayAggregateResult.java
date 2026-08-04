package com.tengencorp.tengen.dto;

/** Aggregate calculation captured by one replay outcome. */
public record ReplayAggregateResult(
        String ruleType,
        String function,
        double value,
        double threshold,
        int windowSeconds,
        String groupKey) {
}
