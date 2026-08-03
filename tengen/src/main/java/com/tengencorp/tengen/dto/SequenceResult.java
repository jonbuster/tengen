package com.tengencorp.tengen.dto;

import java.util.List;

/** Details of a completed sequence match. */
public record SequenceResult(
        String groupKey,
        int windowSeconds,
        List<SequenceStepMatch> steps) {

    public SequenceResult {
        steps = steps != null ? List.copyOf(steps) : List.of();
    }
}
