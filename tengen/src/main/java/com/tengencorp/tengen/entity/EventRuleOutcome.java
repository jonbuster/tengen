package com.tengencorp.tengen.entity;

import com.tengencorp.tengen.dto.AggregateResult;
import com.tengencorp.tengen.dto.AbsenceResult;
import com.tengencorp.tengen.dto.SequenceResult;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable matched-rule trace captured during event ingestion. */
@Entity
@Table(name = "event_rule_outcomes",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_event_rule_outcomes_event_rule_revision",
        columnNames = {"event_id", "rule_id", "rule_revision"}),
    indexes = {
        @Index(name = "idx_event_rule_outcomes_event", columnList = "event_id, id"),
        @Index(name = "idx_event_rule_outcomes_delivery", columnList = "delivery_id")
    })
@Getter
@NoArgsConstructor
public class EventRuleOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /** Rule metadata is copied so history remains useful after later edits. */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_revision", nullable = false)
    private Integer ruleRevision;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private RuleType ruleType;

    @Column(name = "group_key", length = 500)
    private String groupKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aggregate_result", columnDefinition = "jsonb")
    private Map<String, Object> aggregateResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sequence_result", columnDefinition = "jsonb")
    private Map<String, Object> sequenceResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "absence_result", columnDefinition = "jsonb")
    private Map<String, Object> absenceResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_outcome", nullable = false, length = 30)
    private EventRuleActionOutcome actionOutcome;

    @Column(name = "suppression_reason", length = 80)
    private String suppressionReason;

    /** Nullable for log-only and suppressed actions. */
    @Column(name = "delivery_id")
    private Long deliveryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EventRuleOutcome(Event event, Long ruleId, int ruleRevision, String ruleName,
                            RuleType ruleType, String groupKey,
                            AggregateResult aggregateResult, SequenceResult sequenceResult,
                            EventRuleActionOutcome actionOutcome, String suppressionReason,
                            Long deliveryId) {
        this(event, ruleId, ruleRevision, ruleName, ruleType, groupKey,
            aggregateResult, sequenceResult, null, actionOutcome, suppressionReason, deliveryId);
    }

    public EventRuleOutcome(Event event, Long ruleId, int ruleRevision, String ruleName,
                            RuleType ruleType, String groupKey,
                            AggregateResult aggregateResult, SequenceResult sequenceResult,
                            AbsenceResult absenceResult,
                            EventRuleActionOutcome actionOutcome, String suppressionReason,
                            Long deliveryId) {
        this.event = event;
        this.ruleId = ruleId;
        this.ruleRevision = ruleRevision;
        this.ruleName = ruleName;
        this.ruleType = ruleType;
        this.groupKey = groupKey;
        this.aggregateResult = aggregateResult != null ? toMap(aggregateResult) : null;
        this.sequenceResult = sequenceResult != null ? toMap(sequenceResult) : null;
        this.absenceResult = absenceResult != null ? toMap(absenceResult) : null;
        this.actionOutcome = actionOutcome;
        this.suppressionReason = suppressionReason;
        this.deliveryId = deliveryId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private static Map<String, Object> toMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof AggregateResult aggregate) {
            result.put("ruleType", aggregate.ruleType());
            result.put("function", aggregate.function());
            result.put("value", aggregate.value());
            result.put("threshold", aggregate.threshold());
            result.put("windowSeconds", aggregate.windowSeconds());
            result.put("groupKey", aggregate.groupKey());
            return result;
        }
        if (value instanceof SequenceResult sequence) {
            result.put("groupKey", sequence.groupKey());
            result.put("windowSeconds", sequence.windowSeconds());
            result.put("steps", sequence.steps().stream().map(step -> {
                Map<String, Object> stepResult = new LinkedHashMap<>();
                stepResult.put("position", step.position());
                stepResult.put("eventId", step.eventId());
                stepResult.put("occurredAt",
                    step.occurredAt() != null ? step.occurredAt().toString() : null);
                return stepResult;
            }).toList());
            return result;
        }
        if (value instanceof AbsenceResult absence) {
            result.put("instanceId", absence.instanceId());
            result.put("groupKey", absence.groupKey());
            result.put("startEventId", absence.startEventId());
            result.put("startOccurredAt",
                absence.startOccurredAt() != null ? absence.startOccurredAt().toString() : null);
            result.put("expectedEventType", absence.expectedEventType());
            result.put("expectedSource", absence.expectedSource());
            result.put("deadlineAt",
                absence.deadlineAt() != null ? absence.deadlineAt().toString() : null);
            result.put("triggeringWatermark",
                absence.triggeringWatermark() != null ? absence.triggeringWatermark().toString() : null);
            return result;
        }
        throw new IllegalArgumentException("Unsupported event rule outcome snapshot");
    }
}
