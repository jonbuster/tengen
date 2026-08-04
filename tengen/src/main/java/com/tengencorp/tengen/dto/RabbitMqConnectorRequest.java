package com.tengencorp.tengen.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Mutable connector settings. The password is write-only and may be blank on update. */
public record RabbitMqConnectorRequest(
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(max = 255) String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotBlank @Size(max = 255) String virtualHost,
        boolean tlsEnabled,
        @NotBlank @Size(max = 255) String username,
        @Size(max = 1000) String password,
        @NotBlank @Size(max = 255) String queueName,
        @NotBlank @Size(max = 255) String deadLetterExchange,
        @NotBlank @Size(max = 255) String deadLetterRoutingKey,
        @NotNull @Positive Long apiKeyId,
        @NotNull @Min(1) @Max(10485760) Integer maxBodyBytes,
        @NotNull @Min(1) @Max(20) Integer retryAttempts,
        @NotNull @Min(0) @Max(600000) Long retryInitialDelayMs,
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") Double retryMultiplier,
        @NotNull @Min(0) @Max(3600000) Long retryMaxDelayMs,
        Long configurationVersion) {
}
