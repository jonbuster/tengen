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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Event assigned to one position of a sequence instance. */
@Entity
@Table(name = "rule_sequence_instance_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_rule_sequence_instance_events_instance_position",
        columnNames = {"instance_id", "step_position"}))
@Getter
@Setter
@NoArgsConstructor
public class RuleSequenceInstanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private RuleSequenceInstance instance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "step_position", nullable = false)
    private Integer stepPosition;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public RuleSequenceInstanceEvent(RuleSequenceInstance instance, Event event,
                                     int stepPosition, Instant occurredAt) {
        this.instance = instance;
        this.event = event;
        this.stepPosition = stepPosition;
        this.occurredAt = occurredAt;
    }
}
