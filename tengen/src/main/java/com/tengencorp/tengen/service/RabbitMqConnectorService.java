package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.RabbitMqConnectionTestResponse;
import com.tengencorp.tengen.dto.RabbitMqConnectorRequest;
import com.tengencorp.tengen.dto.RabbitMqConnectorResponse;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.entity.RabbitMqConnectorRuntimeState;
import com.tengencorp.tengen.exception.ConflictException;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.exception.RabbitMqConnectorException;
import com.tengencorp.tengen.repository.ApiKeyRepository;
import com.tengencorp.tengen.repository.RabbitMqConnectorRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** CRUD and lifecycle policy for the single RabbitMQ connector. */
@Service
public class RabbitMqConnectorService {

    private final RabbitMqConnectorRepository connectorRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final RabbitMqSecretService secretService;
    private final RabbitMqConnectionService connectionService;
    private final RabbitMqRuntimeManager runtimeManager;

    public RabbitMqConnectorService(
            RabbitMqConnectorRepository connectorRepository,
            ApiKeyRepository apiKeyRepository,
            RabbitMqSecretService secretService,
            RabbitMqConnectionService connectionService,
            RabbitMqRuntimeManager runtimeManager) {
        this.connectorRepository = connectorRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.secretService = secretService;
        this.connectionService = connectionService;
        this.runtimeManager = runtimeManager;
    }

    @Transactional(readOnly = true)
    public RabbitMqConnectorResponse get() {
        return connectorRepository.findFirstByOrderByIdAsc()
            .map(this::response)
            .orElseGet(RabbitMqConnectorResponse::unconfigured);
    }

    @Transactional
    public RabbitMqConnectorResponse save(RabbitMqConnectorRequest request, String ifMatch) {
        RabbitMqConnector connector = connectorRepository.findFirstByOrderByIdAsc()
            .orElseGet(this::newConnector);
        var currentRuntime = connector.getId() == null
            ? null : runtimeManager.status(connector.getId(), connector.isEnabled());
        if (connector.isEnabled() || (currentRuntime != null
                && (currentRuntime.state() == RabbitMqConnectorRuntimeState.RUNNING
                    || currentRuntime.state() == RabbitMqConnectorRuntimeState.CONNECTING
                    || currentRuntime.state() == RabbitMqConnectorRuntimeState.TESTING))) {
            throw new ConflictException("Disable the RabbitMQ connector before editing its configuration");
        }
        checkVersion(connector, request.configurationVersion(), ifMatch);
        if (connector.getId() != null) {
            runtimeManager.stop(connector.getId());
        }
        ApiKey apiKey = resolveApiKey(request.apiKeyId());
        validateApiKeyUsable(apiKey);

        connector.setDisplayName(request.displayName().trim());
        connector.setHost(request.host().trim());
        connector.setPort(request.port());
        connector.setVirtualHost(request.virtualHost().trim());
        connector.setTlsEnabled(request.tlsEnabled());
        connector.setUsername(request.username().trim());
        connector.setQueueName(request.queueName().trim());
        connector.setDeadLetterExchange(request.deadLetterExchange().trim());
        connector.setDeadLetterRoutingKey(request.deadLetterRoutingKey().trim());
        connector.setApiKey(apiKey);
        connector.setMaxBodyBytes(request.maxBodyBytes());
        connector.setRetryAttempts(request.retryAttempts());
        connector.setRetryInitialDelayMs(request.retryInitialDelayMs());
        connector.setRetryMultiplier(request.retryMultiplier());
        connector.setRetryMaxDelayMs(request.retryMaxDelayMs());
        connectionService.validateTarget(connector);

        if (request.password() != null && !request.password().isBlank()) {
            var encrypted = secretService.encrypt(connector.getConnectorKey(), request.password());
            connector.setPasswordCiphertext(encrypted.ciphertext());
            connector.setPasswordNonce(encrypted.nonce());
            connector.setEncryptionKeyVersion(encrypted.keyVersion());
        }
        if (connector.getId() != null) {
            connector.setConfigurationVersion(connector.getConfigurationVersion() + 1);
        } else {
            connector.setConfigurationVersion(1);
        }
        connector.setLastTestedVersion(null);
        connector.setLastTestedAt(null);
        connector.setLastTestSucceeded(null);
        connector.setLastTestErrorCategory(null);
        connector.setEnabled(false);
        connector = connectorRepository.save(connector);
        return response(connector);
    }

    @Transactional
    public RabbitMqConnectionTestResponse test() {
        RabbitMqConnector connector = requireConnector();
        runtimeManager.markTesting(connector.getId());
        Instant testedAt = Instant.now();
        try {
            connectionService.test(connector);
            connector.setLastTestedVersion(connector.getConfigurationVersion());
            connector.setLastTestedAt(testedAt);
            connector.setLastTestSucceeded(true);
            connector.setLastTestErrorCategory(null);
            connectorRepository.save(connector);
            runtimeManager.markTestFinished(connector.getId(), true, null);
            return new RabbitMqConnectionTestResponse(true, "SUCCESS",
                "RabbitMQ connection, queue, and dead-letter exchange are available",
                testedAt, connector.getConfigurationVersion());
        } catch (RabbitMqConnectorException exception) {
            connector.setLastTestedVersion(null);
            connector.setLastTestedAt(testedAt);
            connector.setLastTestSucceeded(false);
            connector.setLastTestErrorCategory(exception.category());
            connectorRepository.save(connector);
            runtimeManager.markTestFinished(connector.getId(), false, exception.category());
            return new RabbitMqConnectionTestResponse(false, exception.category(),
                safeTestMessage(exception.category()), testedAt, connector.getConfigurationVersion());
        }
    }

    @Transactional
    public RabbitMqConnectorResponse enable() {
        RabbitMqConnector connector = requireConnector();
        validateApiKeyUsable(connector.getApiKey());
        connectionService.validateTarget(connector);
        if (connector.getLastTestedVersion() == null
                || connector.getLastTestedVersion() != connector.getConfigurationVersion()
                || !Boolean.TRUE.equals(connector.getLastTestSucceeded())) {
            throw new ConflictException("Test the current RabbitMQ configuration before enabling it");
        }
        if (connector.isEnabled()) {
            return response(connector);
        }
        connector.setEnabled(true);
        try {
            runtimeManager.start(connector);
            connectorRepository.save(connector);
            return response(connector);
        } catch (RabbitMqConnectorException exception) {
            connector.setEnabled(false);
            connectorRepository.save(connector);
            throw new ConflictException(safeTestMessage(exception.category()));
        }
    }

    @Transactional
    public RabbitMqConnectorResponse disable() {
        RabbitMqConnector connector = connectorRepository.findFirstByOrderByIdAsc()
            .orElseGet(this::newConnector);
        if (connector.getId() != null) {
            runtimeManager.stop(connector.getId());
            connector.setEnabled(false);
            connectorRepository.save(connector);
            return response(connector);
        }
        return RabbitMqConnectorResponse.unconfigured();
    }

    private RabbitMqConnector requireConnector() {
        return connectorRepository.findFirstByOrderByIdAsc()
            .orElseThrow(() -> new NotFoundException("RabbitMQ connector has not been configured"));
    }

    private RabbitMqConnectorResponse response(RabbitMqConnector connector) {
        var status = runtimeManager.status(connector.getId(), connector.isEnabled());
        return RabbitMqConnectorResponse.from(connector, status.state(),
            status.errorCategory(), status.lastTransitionAt());
    }

    private RabbitMqConnector newConnector() {
        RabbitMqConnector connector = new RabbitMqConnector();
        connector.setConnectorKey(RabbitMqConnector.DEFAULT_CONNECTOR_KEY);
        connector.setDisplayName("RabbitMQ connector");
        connector.setHost("");
        connector.setPort(5672);
        connector.setVirtualHost("/");
        connector.setUsername("");
        connector.setQueueName("");
        connector.setDeadLetterExchange("");
        connector.setDeadLetterRoutingKey("");
        connector.setMaxBodyBytes(1_048_576);
        connector.setRetryAttempts(3);
        connector.setRetryInitialDelayMs(1_000);
        connector.setRetryMultiplier(2.0);
        connector.setRetryMaxDelayMs(30_000);
        connector.setConfigurationVersion(1);
        return connector;
    }

    private ApiKey resolveApiKey(Long id) {
        return apiKeyRepository.findById(id)
            .orElseThrow(() -> new AccessDeniedException("The selected API key is invalid"));
    }

    private void validateApiKeyUsable(ApiKey apiKey) {
        if (apiKey == null || !apiKey.isActive()
                || (apiKey.getExpiresAt() != null && !apiKey.getExpiresAt().isAfter(Instant.now()))) {
            throw new ConflictException("The selected API key is inactive or expired");
        }
    }

    private void checkVersion(RabbitMqConnector connector, Long requestVersion, String ifMatch) {
        Long expected = requestVersion != null ? requestVersion : parseIfMatch(ifMatch);
        if (expected != null && expected != connector.getConfigurationVersion()) {
            throw new ConflictException("The RabbitMQ connector configuration changed in another tab");
        }
    }

    private Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) return null;
        String value = ifMatch.trim().replace("\"", "");
        try { return Long.valueOf(value); }
        catch (NumberFormatException exception) {
            throw new ConflictException("The RabbitMQ connector configuration version is invalid");
        }
    }

    private String safeTestMessage(String category) {
        return switch (category) {
            case "AUTHENTICATION_FAILED" -> "RabbitMQ authentication or virtual-host access failed.";
            case "QUEUE_MISSING" -> "The configured input queue was not found.";
            case "DEAD_LETTER_EXCHANGE_MISSING" -> "The configured dead-letter exchange was not found.";
            case "MASTER_KEY_MISSING" -> "Configure the connector encryption master key before testing.";
            case "PASSWORD_NOT_CONFIGURED" -> "Enter a RabbitMQ password before testing.";
            case "HOST_NOT_ALLOWED", "ALLOWED_HOSTS_REQUIRED" -> "The broker host is blocked by deployment policy.";
            default -> "RabbitMQ connection test failed. Check the saved settings and broker availability.";
        };
    }
}
