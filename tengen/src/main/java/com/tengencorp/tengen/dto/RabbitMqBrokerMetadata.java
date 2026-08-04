package com.tengencorp.tengen.dto;

import java.time.Instant;

/** Safe AMQP metadata shown only on an event detail page. */
public record RabbitMqBrokerMetadata(
        Long connectorId,
        String connectorKey,
        String connectorName,
        String queueName,
        String sourceExchange,
        String routingKey,
        String messageId,
        Instant processedAt) {
}
