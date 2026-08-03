package com.tengencorp.tengen.dto;

import java.time.Instant;

/** Diagnostic result for one simulated sequence step. */
public record SequenceStepTestResult(
        int position,
        boolean conditionMatched,
        Instant occurredAt) {
}
