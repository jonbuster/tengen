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

/** Persisted configuration for one position in a sequence rule. */
@Entity
@Table(name = "rule_sequence_steps",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_rule_sequence_steps_rule_position",
        columnNames = {"rule_id", "position"}))
@Getter
@Setter
@NoArgsConstructor
public class RuleSequenceStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @Column(nullable = false)
    private Integer position;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "condition_script", nullable = false, columnDefinition = "text")
    private String conditionScript;

    public RuleSequenceStep(Rule rule, Integer position, String eventType,
                            String source, String conditionScript) {
        this.rule = rule;
        this.position = position;
        this.eventType = eventType;
        this.source = source;
        this.conditionScript = conditionScript;
    }
}
