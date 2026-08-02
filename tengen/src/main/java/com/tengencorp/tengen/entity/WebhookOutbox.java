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

/**
 * Immutable webhook delivery intent created during event processing.
 *
 * <p>The future delivery worker owns the status and attempt fields. Event
 * ingestion only creates a {@link WebhookOutboxStatus#PENDING} row.</p>
 */
@Entity
@Table(name = "webhook_outbox",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_webhook_outbox_deduplication_key",
        columnNames = "deduplication_key"),
    indexes = {
        @Index(name = "idx_webhook_outbox_status_next_attempt",
            columnList = "status, next_attempt_at, id"),
        @Index(name = "idx_webhook_outbox_rule_created",
            columnList = "rule_id, created_at"),
        @Index(name = "idx_webhook_outbox_event", columnList = "event_id")
    })
@Getter
@Setter
@NoArgsConstructor
public class WebhookOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /** Nullable so deleting a rule does not delete or strand queued work. */
    @Column(name = "rule_id")
    private Long ruleId;

    /** Immutable snapshot used for history after a rule is edited or deleted. */
    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    /** Immutable destination snapshot; queued work must not follow later rule edits. */
    @Column(name = "callback_url", nullable = false, columnDefinition = "text")
    private String callbackUrl;

    /** Immutable webhook body snapshot. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    /** Empty string is the shared scope for global rules. */
    @Column(name = "scope_key", nullable = false, length = 500)
    private String scopeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode", nullable = false, length = 20)
    private TriggerMode triggerMode;

    @Column(name = "window_start")
    private Instant windowStart;

    /** Stable identity for one logical webhook action. */
    @Column(name = "deduplication_key", nullable = false, length = 1000)
    private String deduplicationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WebhookOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "manually_retried_at")
    private Instant manuallyRetriedAt;

    /** Lease token used to reject stale worker finalization after recovery. */
    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /** Immutable cooldown configuration snapshot for worker finalization. */
    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WebhookOutbox(Event event, Long ruleId, String ruleName, String callbackUrl,
                         Map<String, Object> payload, String scopeKey, TriggerMode triggerMode,
                         Instant windowStart, Integer cooldownSeconds, String deduplicationKey) {
        this.event = event;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.callbackUrl = callbackUrl;
        this.payload = payload;
        this.scopeKey = scopeKey;
        this.triggerMode = triggerMode;
        this.windowStart = windowStart;
        this.cooldownSeconds = cooldownSeconds;
        this.deduplicationKey = deduplicationKey;
        this.status = WebhookOutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = Instant.now();
    }

    /** Backward-compatible constructor for outbox rows created before worker metadata. */
    public WebhookOutbox(Event event, Long ruleId, String ruleName, String callbackUrl,
                         Map<String, Object> payload, String scopeKey, TriggerMode triggerMode,
                         Instant windowStart, String deduplicationKey) {
        this(event, ruleId, ruleName, callbackUrl, payload, scopeKey, triggerMode,
            windowStart, null, deduplicationKey);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (status == null) {
            status = WebhookOutboxStatus.PENDING;
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
