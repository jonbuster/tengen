package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "replay_job_outcomes",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_replay_job_outcomes_job_position",
        columnNames = {"job_id", "input_position"}),
    indexes = {
        @Index(name = "idx_replay_job_outcomes_job_position", columnList = "job_id, input_position"),
        @Index(name = "idx_replay_job_outcomes_job_matched_position",
            columnList = "job_id, matched, input_position")
    })
@Getter
@Setter
@NoArgsConstructor
public class ReplayJobOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "input_position", nullable = false)
    private Long inputPosition;

    @Column(name = "original_event_id")
    private Long originalEventId;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private boolean matched;

    @Column(name = "group_key", length = 500)
    private String groupKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aggregate_result", columnDefinition = "jsonb")
    private Map<String, Object> aggregateResult;

    @Column(name = "error_category", length = 80)
    private String errorCategory;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;
}
