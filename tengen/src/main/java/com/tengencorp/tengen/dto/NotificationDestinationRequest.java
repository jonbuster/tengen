package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Admin request for one email or SMS provider connection. */
public record NotificationDestinationRequest(
        @NotBlank(message = "Display name is required")
        @Size(max = 100, message = "Display name must be at most 100 characters")
        String displayName,

        @NotNull(message = "Channel is required")
        NotificationChannel channel,

        @NotBlank(message = "Provider is required")
        @Size(max = 40, message = "Provider must be at most 40 characters")
        String provider,

        Map<String, Object> configuration,

        Map<String, String> credentials,

        boolean enabled) {
}
