package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Immutable audit snapshot for one rule revision. */
@Entity
@Table(name = "rule_revisions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_rule_revisions_rule_revision",
        columnNames = {"rule_id", "revision"}),
    indexes = @Index(name = "idx_rule_revisions_rule_changed", columnList = "rule_id, changed_at"))
@Getter
@Setter
@NoArgsConstructor
public class RuleRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Scalar ID deliberately survives rule archival and deletion. */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private RuleRevisionChangeType changeType;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "restored_from_revision")
    private Integer restoredFromRevision;

    @Column(name = "snapshot_schema_version", nullable = false)
    private Integer snapshotSchemaVersion = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> snapshot;

    public RuleRevision(Long ruleId, int revision, RuleRevisionChangeType changeType,
                        String actor, Map<String, Object> snapshot, Integer restoredFromRevision) {
        this.ruleId = ruleId;
        this.revision = revision;
        this.changeType = changeType;
        this.actor = actor;
        this.snapshot = snapshot;
        this.restoredFromRevision = restoredFromRevision;
    }

    @PrePersist
    void onCreate() {
        if (changedAt == null) {
            changedAt = Instant.now();
        }
        if (snapshotSchemaVersion == null) {
            snapshotSchemaVersion = 1;
        }
    }
}
