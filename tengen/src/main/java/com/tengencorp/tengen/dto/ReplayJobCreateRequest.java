package com.tengencorp.tengen.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ReplayJobCreateRequest(
        @NotNull Long ruleId,
        @NotNull Integer ruleRevision,
        @NotNull Instant occurredFrom,
        @NotNull Instant occurredTo,
        Long apiKeyId) {
}
