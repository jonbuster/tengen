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

import java.time.Instant;

@Entity
@Table(name = "replay_job_transitions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_replay_job_transitions_job_sequence",
        columnNames = {"job_id", "transition_sequence"}),
    indexes = @Index(name = "idx_replay_job_transitions_job_time",
        columnList = "job_id, transitioned_at, id"))
@Getter
@Setter
@NoArgsConstructor
public class ReplayJobTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "transition_sequence", nullable = false)
    private Long sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private ReplayJobStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private ReplayJobStatus toStatus;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(length = 80)
    private String reason;

    @Column(name = "transitioned_at", nullable = false)
    private Instant transitionedAt;

    public ReplayJobTransition(Long jobId, Long sequence, ReplayJobStatus fromStatus,
                               ReplayJobStatus toStatus, String action, String actor,
                               int attemptCount, String reason) {
        this.jobId = jobId;
        this.sequence = sequence;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.action = action;
        this.actor = actor;
        this.attemptCount = attemptCount;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        if (transitionedAt == null) {
            transitionedAt = Instant.now();
        }
        if (actor == null || actor.isBlank()) {
            actor = "system";
        }
        if (attemptCount < 0) {
            attemptCount = 0;
        }
    }
}
