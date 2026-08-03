package com.tengencorp.tengen.dto;

import java.time.Instant;

/** Persisted or simulated event matched at one sequence position. */
public record SequenceStepMatch(
        int position,
        Long eventId,
        Instant occurredAt) {
}
