package com.tengencorp.tengen.service;

import com.rabbitmq.client.Channel;
import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.exception.RabbitMqConnectorException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;

/** Builds connector-scoped AMQP resources and performs passive connection tests. */
@Service
public class RabbitMqConnectionService {

    private final RabbitMqSecretService secretService;
    private final Environment environment;
    private final String allowedHosts;
    private final int connectionTimeoutMs;
    private final int handshakeTimeoutMs;
    private final int shutdownTimeoutMs;

    public RabbitMqConnectionService(
            RabbitMqSecretService secretService,
            Environment environment,
            @Value("${tengen.rabbitmq.allowed-hosts:}") String allowedHosts,
            @Value("${tengen.rabbitmq.connection-timeout-ms:5000}") int connectionTimeoutMs,
            @Value("${tengen.rabbitmq.handshake-timeout-ms:5000}") int handshakeTimeoutMs,
            @Value("${tengen.rabbitmq.shutdown-timeout-ms:5000}") int shutdownTimeoutMs) {
        this.secretService = secretService;
        this.environment = environment;
        this.allowedHosts = allowedHosts == null ? "" : allowedHosts;
        this.connectionTimeoutMs = bounded(connectionTimeoutMs, 500, 30_000);
        this.handshakeTimeoutMs = bounded(handshakeTimeoutMs, 500, 30_000);
        this.shutdownTimeoutMs = bounded(shutdownTimeoutMs, 500, 30_000);
    }

    public void validateTarget(RabbitMqConnector connector) {
        String host = connector.getHost();
        if (host == null || host.isBlank() || host.length() > 255
                || host.contains("@") || host.contains("/") || host.contains("\\")
                || host.contains("://") || host.chars().anyMatch(Character::isWhitespace)) {
            throw new RabbitMqConnectorException("INVALID_HOST", "RabbitMQ host is invalid");
        }
        String virtualHost = connector.getVirtualHost();
        if (virtualHost == null || virtualHost.isBlank() || virtualHost.length() > 255
                || virtualHost.contains("@") || virtualHost.contains("://")
                || virtualHost.chars().anyMatch(Character::isWhitespace)) {
            throw new RabbitMqConnectorException("INVALID_VIRTUAL_HOST", "RabbitMQ virtual host is invalid");
        }
        validateName(connector.getQueueName(), "INVALID_QUEUE", "RabbitMQ queue name is invalid");
        validateName(connector.getDeadLetterExchange(), "INVALID_DEAD_LETTER_EXCHANGE",
            "RabbitMQ dead-letter exchange is invalid");
        validateName(connector.getDeadLetterRoutingKey(), "INVALID_DEAD_LETTER_ROUTING_KEY",
            "RabbitMQ dead-letter routing key is invalid");
        if (isProduction() && parseAllowedHosts().length == 0) {
            throw new RabbitMqConnectorException("ALLOWED_HOSTS_REQUIRED",
                "RabbitMQ allowed hosts must be configured in production");
        }
        String[] configuredHosts = parseAllowedHosts();
        if (configuredHosts.length > 0 && Arrays.stream(configuredHosts)
                .noneMatch(allowed -> allowed.equalsIgnoreCase(host))) {
            throw new RabbitMqConnectorException("HOST_NOT_ALLOWED",
                "RabbitMQ host is not allowed by deployment policy");
        }
    }

    public CachingConnectionFactory createConnectionFactory(RabbitMqConnector connector) {
        validateTarget(connector);
        String password = secretService.decrypt(connector);
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setHost(connector.getHost());
        factory.setPort(connector.getPort());
        factory.setVirtualHost(connector.getVirtualHost());
        factory.setUsername(connector.getUsername());
        factory.setPassword(password);
        factory.setConnectionTimeout(connectionTimeoutMs);
        factory.setCloseTimeout(shutdownTimeoutMs);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        factory.setChannelCacheSize(2);
        factory.getRabbitConnectionFactory().setHandshakeTimeout(handshakeTimeoutMs);
        factory.getRabbitConnectionFactory().setShutdownTimeout(shutdownTimeoutMs);
        if (connector.isTlsEnabled()) {
            try {
                factory.getRabbitConnectionFactory().useSslProtocol();
            } catch (Exception exception) {
                throw new RabbitMqConnectorException("TLS_CONFIGURATION_FAILED",
                    "RabbitMQ TLS could not be configured", exception);
            }
        }
        return factory;
    }

    public void test(RabbitMqConnector connector) {
        validateTarget(connector);
        CachingConnectionFactory factory = createConnectionFactory(connector);
        Connection connection = null;
        Channel channel = null;
        try {
            connection = factory.createConnection();
            channel = connection.createChannel(false);
            channel.queueDeclarePassive(connector.getQueueName());
            channel.exchangeDeclarePassive(connector.getDeadLetterExchange());
        } catch (Exception exception) {
            throw classifyTestFailure(exception);
        } finally {
            close(channel);
            close(connection);
            factory.destroy();
        }
    }

    private RabbitMqConnectorException classifyTestFailure(Exception exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("authentication") || message.contains("access refused")
                || message.contains("login refused") || message.contains("not allowed")) {
            return new RabbitMqConnectorException("AUTHENTICATION_FAILED",
                "RabbitMQ authentication or virtual-host access failed");
        }
        if (message.contains("queue") && (message.contains("not found") || message.contains("404"))) {
            return new RabbitMqConnectorException("QUEUE_MISSING", "The configured RabbitMQ queue was not found");
        }
        if (message.contains("exchange") && (message.contains("not found") || message.contains("404"))) {
            return new RabbitMqConnectorException("DEAD_LETTER_EXCHANGE_MISSING",
                "The configured dead-letter exchange was not found");
        }
        return new RabbitMqConnectorException("CONNECTION_FAILED",
            "RabbitMQ connection test failed");
    }

    private void validateName(String value, String category, String message) {
        if (value == null || value.isBlank() || value.length() > 255
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new RabbitMqConnectorException(category, message);
        }
    }

    private String[] parseAllowedHosts() {
        return Arrays.stream(allowedHosts.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toArray(String[]::new);
    }

    private boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                || profile.equalsIgnoreCase("production"));
    }

    private int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void close(Channel channel) {
        if (channel != null) {
            try { channel.close(); } catch (Exception ignored) { }
        }
    }

    private void close(Connection connection) {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) { }
        }
    }
}
