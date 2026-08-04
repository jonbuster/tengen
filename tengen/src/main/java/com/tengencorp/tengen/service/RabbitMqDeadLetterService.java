package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.exception.RabbitMqConnectorException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Publishes original deliveries to the configured dead-letter route with confirms. */
@Service
public class RabbitMqDeadLetterService {

    private final RabbitMqConnectionService connectionService;
    private final long confirmTimeoutMs;

    public RabbitMqDeadLetterService(
            RabbitMqConnectionService connectionService,
            @Value("${tengen.rabbitmq.dead-letter-timeout-ms:5000}") long confirmTimeoutMs) {
        this.connectionService = connectionService;
        this.confirmTimeoutMs = Math.max(500, Math.min(30_000, confirmTimeoutMs));
    }

    public void publish(RabbitMqConnector connector,
                        byte[] body,
                        String messageId,
                        String contentType,
                        String category) {
        CachingConnectionFactory factory = connectionService.createConnectionFactory(connector);
        RabbitTemplate template = new RabbitTemplate(factory);
        template.setMandatory(true);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (messageId != null && !messageId.isBlank() && messageId.length() <= 255) {
            properties.setMessageId(messageId);
        }
        if (contentType != null && !contentType.isBlank() && contentType.length() <= 255) {
            properties.setContentType(contentType);
        }
        properties.setHeader("x-tengen-connector-id", connector.getConnectorKey());
        properties.setHeader("x-tengen-source-queue", connector.getQueueName());
        properties.setHeader("x-tengen-failure-category", boundedCategory(category));
        properties.setHeader("x-tengen-failure-time", Instant.now().toString());
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            template.send(connector.getDeadLetterExchange(), connector.getDeadLetterRoutingKey(),
                new Message(body, properties), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
                throw new RabbitMqConnectorException("DEAD_LETTER_UNROUTABLE",
                    "The RabbitMQ dead-letter publication was not confirmed and routed");
            }
        } catch (RabbitMqConnectorException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RabbitMqConnectorException("DEAD_LETTER_UNAVAILABLE",
                "The RabbitMQ dead-letter publication failed", exception);
        } finally {
            factory.destroy();
        }
    }

    private String boundedCategory(String category) {
        if (category == null || category.isBlank()) return "UNKNOWN";
        String normalized = category.replaceAll("[^A-Z0-9_\\-]", "_");
        return normalized.substring(0, Math.min(80, normalized.length()));
    }
}
