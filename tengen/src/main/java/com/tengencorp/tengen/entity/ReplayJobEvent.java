package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "replay_job_events")
@IdClass(ReplayJobEventId.class)
@Getter
@Setter
@NoArgsConstructor
public class ReplayJobEvent {

    @Id
    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Id
    @Column(nullable = false)
    private Long position;

    @Column(name = "original_event_id")
    private Long originalEventId;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "api_key_id")
    private Long apiKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_event_time_status", length = 20)
    private EventTimeStatus originalEventTimeStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> data;

    @Column(name = "in_requested_range", nullable = false)
    private boolean inRequestedRange;
}
