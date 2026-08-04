package com.tengencorp.tengen.dto;

import java.time.Instant;

/** Details of a triggered absence match. */
public record AbsenceResult(
        Long instanceId,
        String groupKey,
        Long startEventId,
        Instant startOccurredAt,
        String expectedEventType,
        String expectedSource,
        Instant deadlineAt,
        Instant triggeringWatermark) {
}
