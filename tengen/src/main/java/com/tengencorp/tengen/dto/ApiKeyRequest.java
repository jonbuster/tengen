package com.tengencorp.tengen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record ApiKeyRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @Size(max = 100, message = "allowedEventTypes must have at most 100 values")
        List<@NotBlank @Size(max = 100) String> allowedEventTypes,

        @Size(max = 100, message = "allowedSources must have at most 100 values")
        List<@NotBlank @Size(max = 100) String> allowedSources,

        Instant expiresAt) {
}
