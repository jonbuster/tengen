package com.tengencorp.tengen.entity;

import com.tengencorp.tengen.entity.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per (rule, event) that passed the pre-filter and condition.
 * Backs the windowed aggregate queries.
 */
@Entity
@Table(name = "rule_events", indexes = {
    @Index(name = "idx_rule_events_rule_occurred", columnList = "rule_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
public class RuleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /**
     * Extracted numeric value of aggField for non-COUNT aggregates; NULL for COUNT / CONDITION rules.
     */
    @Column
    private Double value;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public RuleEvent(Rule rule, Event event, Double value, Instant occurredAt) {
        this.rule = rule;
        this.event = event;
        this.value = value;
        this.occurredAt = occurredAt;
    }
}
