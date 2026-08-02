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

/** Durable delivery state for one event-time window and webhook scope. */
@Entity
@Table(name = "rule_action_windows", uniqueConstraints = @UniqueConstraint(
    name = "uk_rule_action_window_rule_revision_scope_start",
    columnNames = {"rule_id", "rule_revision", "scope_key", "window_start"}))
@Getter
@Setter
@NoArgsConstructor
public class RuleActionWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @Column(name = "rule_revision")
    private Integer ruleRevision;

    /** Empty string is the shared scope for global rules. */
    @Column(name = "scope_key", nullable = false, length = 500)
    private String scopeKey;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** Outbox row reserving this window before delivery succeeds. */
    @Column(name = "pending_outbox_id")
    private Long pendingOutboxId;

    public RuleActionWindow(Rule rule, String scopeKey, Instant windowStart) {
        this.rule = rule;
        this.ruleRevision = rule != null ? rule.getEffectiveRevision() : 1;
        this.scopeKey = scopeKey;
        this.windowStart = windowStart;
    }

    public int getEffectiveRuleRevision() {
        return ruleRevision != null && ruleRevision > 0 ? ruleRevision : 1;
    }
}
