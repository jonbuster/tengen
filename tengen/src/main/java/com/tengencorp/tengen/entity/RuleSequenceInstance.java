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

/** Runtime progress for one ordered sequence instance. */
@Entity
@Table(name = "rule_sequence_instances")
@Getter
@Setter
@NoArgsConstructor
public class RuleSequenceInstance {

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

    @Column(name = "next_step_position")
    private Integer nextStepPosition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleSequenceInstanceStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_occurred_at", nullable = false)
    private Instant lastOccurredAt;

    @Column(name = "last_event_id", nullable = false)
    private Long lastEventId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RuleSequenceInstance(Rule rule, String scopeKey, int nextStepPosition,
                                Instant occurredAt, Long eventId) {
        this.rule = rule;
        this.ruleRevision = rule != null ? rule.getEffectiveRevision() : 1;
        this.scopeKey = scopeKey;
        this.nextStepPosition = nextStepPosition;
        this.status = RuleSequenceInstanceStatus.ACTIVE;
        this.startedAt = occurredAt;
        this.lastOccurredAt = occurredAt;
        this.lastEventId = eventId;
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
            status = RuleSequenceInstanceStatus.ACTIVE;
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
