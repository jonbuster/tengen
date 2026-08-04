package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.ReplayJobStatus;
import com.tengencorp.tengen.entity.ReplayJobTransition;

import java.time.Instant;

public record ReplayJobTransitionResponse(
        Long id,
        long sequence,
        ReplayJobStatus fromStatus,
        ReplayJobStatus toStatus,
        String action,
        String actor,
        int attemptCount,
        String reason,
        Instant transitionedAt) {

    public static ReplayJobTransitionResponse from(ReplayJobTransition transition) {
        return new ReplayJobTransitionResponse(
            transition.getId(),
            transition.getSequence(),
            transition.getFromStatus(),
            transition.getToStatus(),
            transition.getAction(),
            transition.getActor(),
            transition.getAttemptCount(),
            transition.getReason(),
            transition.getTransitionedAt());
    }
}
