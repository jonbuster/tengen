package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.ReplayJobOutcome;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

public record ReplayJobOutcomeResponse(
        Long id,
        Long originalEventId,
        long inputPosition,
        String type,
        String source,
        Instant occurredAt,
        boolean matched,
        String groupKey,
        ReplayAggregateResult aggregate,
        String errorCategory,
        Instant completedAt) {

    public static ReplayJobOutcomeResponse from(ReplayJobOutcome outcome,
                                                 ObjectMapper objectMapper) {
        ReplayAggregateResult aggregate = outcome.getAggregateResult() == null
            ? null
            : objectMapper.convertValue(outcome.getAggregateResult(), ReplayAggregateResult.class);
        return new ReplayJobOutcomeResponse(
            outcome.getId(),
            outcome.getOriginalEventId(),
            outcome.getInputPosition(),
            outcome.getType(),
            outcome.getSource(),
            outcome.getOccurredAt(),
            outcome.isMatched(),
            outcome.getGroupKey(),
            aggregate,
            outcome.getErrorCategory(),
            outcome.getCompletedAt());
    }
}
