package com.tengencorp.tengen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record ApiKeyRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        List<String> allowedEventTypes,

        List<String> allowedSources,

        Instant expiresAt) {
}
