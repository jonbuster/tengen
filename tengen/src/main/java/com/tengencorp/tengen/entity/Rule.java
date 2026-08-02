package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

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

    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode", length = 20)
    private TriggerMode triggerMode = TriggerMode.EVERY_MATCH;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "condition_script", nullable = false, columnDefinition = "text")
    private String conditionScript;

    @Column(name = "window_seconds")
    private Integer windowSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "agg_type", length = 20)
    private AggregateType aggType;

    @Column(name = "agg_field", length = 200)
    private String aggField;

    @Column(name = "group_by", length = 200)
    private String groupBy;

    @Column(nullable = false)
    private Double threshold = 0.0;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TriggerMode getEffectiveTriggerMode() {
        return triggerMode != null ? triggerMode : TriggerMode.EVERY_MATCH;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
