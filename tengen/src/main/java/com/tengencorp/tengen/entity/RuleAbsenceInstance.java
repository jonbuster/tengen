package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Durable progress for one start event waiting for an expected event. */
@Entity
@Table(name = "rule_absence_instances")
@Getter
@Setter
@NoArgsConstructor
public class RuleAbsenceInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @Column(name = "rule_revision", nullable = false)
    private Integer ruleRevision;

    @Column(name = "scope_key", nullable = false, length = 500)
    private String scopeKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "start_event_id", nullable = false)
    private Event startEvent;

    @Column(name = "start_occurred_at", nullable = false)
    private Instant startOccurredAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleAbsenceInstanceStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_event_id")
    private Event resolvedByEvent;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "delivery_id")
    private Long deliveryId;

    @Column(name = "suppression_reason", length = 80)
    private String suppressionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RuleAbsenceInstance(Rule rule, String scopeKey, Event startEvent,
                               Instant deadlineAt) {
        this.rule = rule;
        this.ruleRevision = rule != null ? rule.getEffectiveRevision() : 1;
        this.scopeKey = scopeKey;
        this.startEvent = startEvent;
        this.startOccurredAt = startEvent != null ? startEvent.getOccurredAt() : null;
        this.deadlineAt = deadlineAt;
        this.status = RuleAbsenceInstanceStatus.PENDING;
    }

    public int getEffectiveRuleRevision() {
        return ruleRevision != null && ruleRevision > 0 ? ruleRevision : 1;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = RuleAbsenceInstanceStatus.PENDING;
        }
        if (ruleRevision == null || ruleRevision < 1) {
            ruleRevision = rule != null ? rule.getEffectiveRevision() : 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
