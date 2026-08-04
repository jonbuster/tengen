package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** The one UI-managed RabbitMQ connector definition. Secrets are encrypted bytes. */
@Entity
@Table(name = "rabbitmq_connectors")
@Getter
@Setter
@NoArgsConstructor
public class RabbitMqConnector {

    public static final String DEFAULT_CONNECTOR_KEY = "rabbitmq-primary";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connector_key", nullable = false, unique = true, length = 80)
    private String connectorKey = DEFAULT_CONNECTOR_KEY;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "virtual_host", nullable = false, length = 255)
    private String virtualHost;

    @Column(name = "tls_enabled", nullable = false)
    private boolean tlsEnabled;

    @Column(nullable = false, length = 255)
    private String username;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "password_ciphertext", columnDefinition = "bytea")
    private byte[] passwordCiphertext;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "password_nonce", columnDefinition = "bytea")
    private byte[] passwordNonce;

    @Column(name = "encryption_key_version")
    private Integer encryptionKeyVersion;

    @Column(name = "queue_name", nullable = false, length = 255)
    private String queueName;

    @Column(name = "dead_letter_exchange", nullable = false, length = 255)
    private String deadLetterExchange;

    @Column(name = "dead_letter_routing_key", nullable = false, length = 255)
    private String deadLetterRoutingKey;

    @ManyToOne
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "max_body_bytes", nullable = false)
    private int maxBodyBytes;

    @Column(name = "retry_attempts", nullable = false)
    private int retryAttempts;

    @Column(name = "retry_initial_delay_ms", nullable = false)
    private long retryInitialDelayMs;

    @Column(name = "retry_multiplier", nullable = false)
    private double retryMultiplier;

    @Column(name = "retry_max_delay_ms", nullable = false)
    private long retryMaxDelayMs;

    @Column(name = "configuration_version", nullable = false)
    private long configurationVersion = 1;

    @Column(name = "last_tested_version")
    private Long lastTestedVersion;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_test_succeeded")
    private Boolean lastTestSucceeded;

    @Column(name = "last_test_error_category", length = 80)
    private String lastTestErrorCategory;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (connectorKey == null || connectorKey.isBlank()) connectorKey = DEFAULT_CONNECTOR_KEY;
        if (configurationVersion < 1) configurationVersion = 1;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
