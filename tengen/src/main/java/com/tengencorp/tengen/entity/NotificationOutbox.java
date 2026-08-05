package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Immutable rendered email/SMS intent created during event processing. */
@Entity
@Table(name = "notification_outbox",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_outbox_deduplication_key",
        columnNames = "deduplication_key"),
    indexes = {
        @Index(name = "idx_notification_outbox_status_next_attempt",
            columnList = "status, next_attempt_at, id"),
        @Index(name = "idx_notification_outbox_rule_created",
            columnList = "rule_id, created_at"),
        @Index(name = "idx_notification_outbox_event", columnList = "event_id")
    })
@Getter
@Setter
@NoArgsConstructor
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_revision", nullable = false)
    private Integer ruleRevision;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Column(name = "destination_id")
    private Long destinationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(nullable = false, length = 40)
    private String provider;

    /** Rendered message and masked recipient metadata; never template references. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> messageSnapshot;

    @Column(name = "scope_key", nullable = false, length = 500)
    private String scopeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode", nullable = false, length = 20)
    private TriggerMode triggerMode;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "deduplication_key", nullable = false, length = 1000)
    private String deduplicationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "manually_retried_at")
    private Instant manuallyRetriedAt;

    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationOutbox(Event event, Long ruleId, String ruleName, Long destinationId,
                              NotificationChannel channel, String provider,
                              Map<String, Object> messageSnapshot, String scopeKey,
                              TriggerMode triggerMode, Instant windowStart,
                              Integer ruleRevision, Integer cooldownSeconds,
                              String deduplicationKey) {
        this.event = event;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.destinationId = destinationId;
        this.channel = channel;
        this.provider = provider;
        this.messageSnapshot = messageSnapshot;
        this.scopeKey = scopeKey;
        this.triggerMode = triggerMode;
        this.windowStart = windowStart;
        this.ruleRevision = ruleRevision;
        this.cooldownSeconds = cooldownSeconds;
        this.deduplicationKey = deduplicationKey;
        this.status = NotificationOutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = Instant.now();
    }

    public int getEffectiveRuleRevision() {
        return ruleRevision != null && ruleRevision > 0 ? ruleRevision : 1;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (status == null) {
            status = NotificationOutboxStatus.PENDING;
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
