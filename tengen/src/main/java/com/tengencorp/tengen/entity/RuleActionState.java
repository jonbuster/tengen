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

/** Durable delivery state for one rule and one webhook cooldown scope. */
@Entity
@Table(name = "rule_action_state", uniqueConstraints = @UniqueConstraint(
    name = "uk_rule_action_state_rule_scope", columnNames = {"rule_id", "scope_key"}))
@Getter
@Setter
@NoArgsConstructor
public class RuleActionState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    /** Empty string is the shared scope for global rules. */
    @Column(name = "scope_key", nullable = false, length = 500)
    private String scopeKey;

    @Column(name = "last_successful_delivery_at")
    private Instant lastSuccessfulDeliveryAt;

    /** Previous logical match state for EDGE webhook triggering. */
    @Column(name = "last_matched")
    private Boolean lastMatched;

    /** Outbox row currently reserving a cooldown- or EDGE-scoped delivery, if any. */
    @Column(name = "pending_outbox_id")
    private Long pendingOutboxId;

    public RuleActionState(Rule rule, String scopeKey) {
        this.rule = rule;
        this.scopeKey = scopeKey;
        this.lastMatched = false;
    }

    public boolean wasLastMatched() {
        return Boolean.TRUE.equals(lastMatched);
    }
}
