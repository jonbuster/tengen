package com.tengencorp.tengen.dto;

import java.util.List;

/** Side-effect-free result for testing all configured sequence steps together. */
public record SequenceTestResult(
        boolean matched,
        boolean correlationMatched,
        boolean orderingValid,
        boolean withinWindow,
        String groupKey,
        List<SequenceStepTestResult> steps,
        SequenceResult sequence) {

    public SequenceTestResult {
        steps = steps != null ? List.copyOf(steps) : List.of();
    }
}
