package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Durable at-least-once receipt keyed by the publisher message id. */
@Entity
@Table(name = "rabbitmq_message_receipts")
@Getter
@Setter
@NoArgsConstructor
public class RabbitMqMessageReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connector_id", nullable = false)
    private RabbitMqConnector connector;

    @Column(name = "queue_name", nullable = false, length = 255)
    private String queueName;

    @Column(name = "message_id", nullable = false, length = 255)
    private String messageId;

    @Column(name = "source_exchange", length = 255)
    private String sourceExchange;

    @Column(name = "routing_key", length = 255)
    private String routingKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
