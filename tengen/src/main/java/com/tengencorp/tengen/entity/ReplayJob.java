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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "replay_jobs", indexes = {
    @Index(name = "idx_replay_jobs_status_created", columnList = "status, created_at, id")
})
@Getter
@Setter
@NoArgsConstructor
public class ReplayJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReplayJobStatus status = ReplayJobStatus.QUEUED;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_revision", nullable = false)
    private Integer ruleRevision;

    @Column(name = "snapshot_schema_version", nullable = false)
    private Integer snapshotSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> ruleSnapshot;

    @Column(name = "occurred_from", nullable = false)
    private Instant occurredFrom;

    @Column(name = "occurred_to", nullable = false)
    private Instant occurredTo;

    @Column(name = "api_key_id")
    private Long apiKeyId;

    @Column(name = "warmup_from", nullable = false)
    private Instant warmupFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_mode", nullable = false, length = 20)
    private ReplayActionMode actionMode = ReplayActionMode.NO_ACTIONS;

    @Column(name = "total_output_events", nullable = false)
    private long totalOutputEvents;

    @Column(name = "total_materialized_events", nullable = false)
    private long totalMaterializedEvents;

    @Column(name = "processed_output_events", nullable = false)
    private long processedOutputEvents;

    @Column(name = "matched_events", nullable = false)
    private long matchedEvents;

    @Column(name = "error_events", nullable = false)
    private long errorEvents;

    @Column(name = "last_committed_position")
    private Long lastCommittedPosition;

    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_category", length = 80)
    private String failureCategory;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

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
            status = ReplayJobStatus.QUEUED;
        }
        if (actionMode == null) {
            actionMode = ReplayActionMode.NO_ACTIONS;
        }
        if (version == null) {
            version = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
