package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.entity.RabbitMqConnectorRuntimeState;

import java.time.Instant;

/** Safe connector state. It deliberately contains no password or encryption material. */
public record RabbitMqConnectorResponse(
        boolean configured,
        Long id,
        String connectorKey,
        String displayName,
        String host,
        int port,
        String virtualHost,
        boolean tlsEnabled,
        String username,
        boolean passwordConfigured,
        String queueName,
        String deadLetterExchange,
        String deadLetterRoutingKey,
        Long apiKeyId,
        String apiKeyName,
        boolean apiKeyActive,
        Instant apiKeyExpiresAt,
        int maxBodyBytes,
        int retryAttempts,
        long retryInitialDelayMs,
        double retryMultiplier,
        long retryMaxDelayMs,
        boolean enabled,
        long configurationVersion,
        Long lastTestedVersion,
        Instant lastTestedAt,
        Boolean lastTestSucceeded,
        String lastTestErrorCategory,
        RabbitMqConnectorRuntimeState runtimeState,
        String errorCategory,
        Instant lastTransitionAt) {

    public static RabbitMqConnectorResponse unconfigured() {
        return new RabbitMqConnectorResponse(
            false, null, RabbitMqConnector.DEFAULT_CONNECTOR_KEY,
            "RabbitMQ connector", "", 5672, "/", false, "", false,
            "", "", "", null, null, false, null,
            1_048_576, 3, 1_000, 2.0, 30_000,
            false, 1, null, null, null, null,
            RabbitMqConnectorRuntimeState.DISABLED, null, null);
    }

    public static RabbitMqConnectorResponse from(
            RabbitMqConnector connector,
            RabbitMqConnectorRuntimeState runtimeState,
            String errorCategory,
            Instant lastTransitionAt) {
        var apiKey = connector.getApiKey();
        return new RabbitMqConnectorResponse(
            true,
            connector.getId(),
            connector.getConnectorKey(),
            connector.getDisplayName(),
            connector.getHost(),
            connector.getPort(),
            connector.getVirtualHost(),
            connector.isTlsEnabled(),
            connector.getUsername(),
            connector.getPasswordCiphertext() != null && connector.getPasswordNonce() != null,
            connector.getQueueName(),
            connector.getDeadLetterExchange(),
            connector.getDeadLetterRoutingKey(),
            apiKey != null ? apiKey.getId() : null,
            apiKey != null ? apiKey.getName() : null,
            apiKey != null && apiKey.isActive(),
            apiKey != null ? apiKey.getExpiresAt() : null,
            connector.getMaxBodyBytes(),
            connector.getRetryAttempts(),
            connector.getRetryInitialDelayMs(),
            connector.getRetryMultiplier(),
            connector.getRetryMaxDelayMs(),
            connector.isEnabled(),
            connector.getConfigurationVersion(),
            connector.getLastTestedVersion(),
            connector.getLastTestedAt(),
            connector.getLastTestSucceeded(),
            connector.getLastTestErrorCategory(),
            runtimeState,
            errorCategory,
            lastTransitionAt);
    }
}
