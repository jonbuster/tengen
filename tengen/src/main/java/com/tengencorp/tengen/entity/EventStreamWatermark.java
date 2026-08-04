package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Durable bounded-out-of-orderness progress for one source and event type. */
@Entity
@Table(name = "event_stream_watermarks", uniqueConstraints = @UniqueConstraint(
    name = "uk_event_stream_watermarks_source_type",
    columnNames = {"source", "event_type"}))
@Getter
@Setter
@NoArgsConstructor
public class EventStreamWatermark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "max_occurred_at")
    private Instant maxOccurredAt;

    @Column(name = "watermark_at", nullable = false)
    private Instant watermarkAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public EventStreamWatermark(String eventType, String source, Instant maxOccurredAt,
                                Instant watermarkAt, Instant now) {
        this.eventType = eventType;
        this.source = source;
        this.maxOccurredAt = maxOccurredAt;
        this.watermarkAt = watermarkAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
