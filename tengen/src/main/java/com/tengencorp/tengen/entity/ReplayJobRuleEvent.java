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

import java.time.Instant;

@Entity
@Table(name = "replay_job_rule_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_replay_job_rule_events_job_position",
        columnNames = {"job_id", "input_position"}),
    indexes = @Index(name = "idx_replay_job_rule_events_job_group_occurred_position",
        columnList = "job_id, group_key, occurred_at, input_position"))
@Getter
@Setter
@NoArgsConstructor
public class ReplayJobRuleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "input_position", nullable = false)
    private Long inputPosition;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "group_key", length = 500)
    private String groupKey;

    @Column
    private Double value;
}
