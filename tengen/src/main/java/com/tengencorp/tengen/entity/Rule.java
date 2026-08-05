package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rules", uniqueConstraints = @UniqueConstraint(name = "uk_rules_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private RuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleAction action = RuleAction.LOG;

    @Column(name = "callback_url", columnDefinition = "text")
    private String callbackUrl;

    /** Reusable email/SMS provider connection selected by a notification rule. */
    @Column(name = "notification_destination_id")
    private Long notificationDestinationId;

    /** Immutable template version selected by a notification rule. */
    @Column(name = "notification_template_id")
    private Long notificationTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_recipient_mode", length = 20)
    private NotificationRecipientMode notificationRecipientMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_recipients", columnDefinition = "jsonb")
    private List<String> notificationRecipients = new ArrayList<>();

    @Column(name = "notification_recipient_field", length = 200)
    private String notificationRecipientField;

    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode", length = 20)
    private TriggerMode triggerMode = TriggerMode.EVERY_MATCH;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(length = 100)
    private String source;

    @Column(name = "condition_script", columnDefinition = "text")
    private String conditionScript;

    @Column(name = "expected_event_type", length = 100)
    private String expectedEventType;

    @Column(name = "expected_source", length = 100)
    private String expectedSource;

    @Column(name = "expected_condition_script", columnDefinition = "text")
    private String expectedConditionScript;

    @Column(name = "window_seconds")
    private Integer windowSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "agg_type", length = 20)
    private AggregateType aggType;

    @Column(name = "agg_field", length = 200)
    private String aggField;

    @Column(name = "group_by", length = 200)
    private String groupBy;

    @OneToMany(mappedBy = "rule", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<RuleSequenceStep> sequenceSteps = new ArrayList<>();

    @Column(nullable = false)
    private Double threshold = 0.0;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 20)
    private RuleValidationStatus validationStatus = RuleValidationStatus.VALID;

    @Column(name = "validation_error", length = 1000)
    private String validationError;

    @Column(nullable = false)
    private Integer revision = 1;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TriggerMode getEffectiveTriggerMode() {
        return triggerMode != null ? triggerMode : TriggerMode.EVERY_MATCH;
    }

    public int getEffectiveRevision() {
        return revision != null && revision > 0 ? revision : 1;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (revision == null || revision < 1) {
            revision = 1;
        }
        if (validationStatus == null) {
            validationStatus = RuleValidationStatus.VALID;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
