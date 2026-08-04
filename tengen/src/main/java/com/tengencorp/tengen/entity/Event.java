package com.tengencorp.tengen.entity;

import com.tengencorp.tengen.entity.ApiKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> data;

    /** API key that ingested this event; null for legacy/ingest-without-key. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_key_id")
    private ApiKey apiKey;

    /** Null for events written before Event Explorer was introduced. */
    @Column(name = "processing_trace_version")
    private Short processingTraceVersion;

    @Column(name = "matched_rule_count")
    private Integer matchedRuleCount;

    @Column(name = "queued_action_count")
    private Integer queuedActionCount;

    @Column(name = "suppressed_action_count")
    private Integer suppressedActionCount;

    /** Event-time classification captured before rule evaluation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_time_status", length = 20)
    private EventTimeStatus eventTimeStatus;

    /** Watermark used to classify this event; null for first events and legacy rows. */
    @Column(name = "watermark_at_decision")
    private Instant watermarkAtDecision;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }

    public Event(String type, String source, Instant occurredAt, Map<String, Object> data) {
        this.type = type;
        this.source = source;
        this.occurredAt = occurredAt;
        this.data = data;
    }

    public Event(String type, String source, Instant occurredAt, Map<String, Object> data, ApiKey apiKey) {
        this(type, source, occurredAt, data);
        this.apiKey = apiKey;
    }

    public void recordProcessingTrace(int matchedRules, int queuedActions, int suppressedActions) {
        this.processingTraceVersion = (short) 1;
        this.matchedRuleCount = matchedRules;
        this.queuedActionCount = queuedActions;
        this.suppressedActionCount = suppressedActions;
    }

    /** Add a delayed outcome to the trace after an absence deadline closes. */
    public void recordDelayedProcessingTrace(int matchedRules, int queuedActions,
                                             int suppressedActions) {
        if (processingTraceVersion == null) {
            recordProcessingTrace(matchedRules, queuedActions, suppressedActions);
            return;
        }
        matchedRuleCount = (matchedRuleCount != null ? matchedRuleCount : 0) + matchedRules;
        queuedActionCount = (queuedActionCount != null ? queuedActionCount : 0) + queuedActions;
        suppressedActionCount = (suppressedActionCount != null ? suppressedActionCount : 0)
            + suppressedActions;
    }
}
