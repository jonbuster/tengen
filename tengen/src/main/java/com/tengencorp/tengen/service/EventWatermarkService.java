package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.EventStreamWatermark;
import com.tengencorp.tengen.entity.EventTimeStatus;
import com.tengencorp.tengen.repository.EventStreamWatermarkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Clock;

/**
 * Classifies events against durable bounded-out-of-orderness progress. The
 * caller owns the ingestion transaction; the row lock serializes only events
 * sharing the same source and event type.
 */
@Service
public class EventWatermarkService {

    private final EventStreamWatermarkRepository repository;
    private final long allowedLatenessSeconds;
    private final Clock clock;

    @Autowired
    public EventWatermarkService(
            EventStreamWatermarkRepository repository,
            @Value("${tengen.ingestion.allowed-lateness-seconds:300}")
            long allowedLatenessSeconds) {
        this(repository, allowedLatenessSeconds, Clock.systemUTC());
    }

    EventWatermarkService(EventStreamWatermarkRepository repository,
                          long allowedLatenessSeconds,
                          Clock clock) {
        if (allowedLatenessSeconds < 0) {
            throw new IllegalArgumentException(
                "tengen.ingestion.allowed-lateness-seconds must be non-negative");
        }
        this.repository = repository;
        this.allowedLatenessSeconds = allowedLatenessSeconds;
        this.clock = clock;
    }

    /**
     * Ensures and locks the stream row, then classifies against the watermark
     * that existed before this event. Newer events advance both max event time
     * and watermark; late events never move either value backwards.
     */
    public EventTimeDecision classify(String eventType, String source, Instant occurredAt) {
        Instant now = clock.instant();
        int inserted = repository.ensureExists(
            eventType,
            source,
            occurredAt,
            occurredAt.minusSeconds(allowedLatenessSeconds),
            now);
        EventStreamWatermark state = repository.findForUpdate(eventType, source)
            .orElseThrow(() -> new IllegalStateException(
                "Event stream watermark was not created"));

        if (inserted == 1) {
            return new EventTimeDecision(EventTimeStatus.ON_TIME, null);
        }

        Instant effectiveWatermark = state.getWatermarkAt();
        if (state.getMaxOccurredAt() != null) {
            Instant calculatedWatermark = state.getMaxOccurredAt()
                .minusSeconds(allowedLatenessSeconds);
            if (calculatedWatermark.isAfter(effectiveWatermark)) {
                effectiveWatermark = calculatedWatermark;
                state.setWatermarkAt(effectiveWatermark);
            }
        }

        EventTimeStatus status;
        if (!occurredAt.isAfter(effectiveWatermark)) {
            status = EventTimeStatus.TOO_LATE;
        } else if (state.getMaxOccurredAt() != null
                && occurredAt.isBefore(state.getMaxOccurredAt())) {
            status = EventTimeStatus.LATE_ACCEPTED;
        } else {
            status = EventTimeStatus.ON_TIME;
        }

        if (state.getMaxOccurredAt() == null || occurredAt.isAfter(state.getMaxOccurredAt())) {
            state.setMaxOccurredAt(occurredAt);
            Instant advancedWatermark = occurredAt.minusSeconds(allowedLatenessSeconds);
            if (advancedWatermark.isAfter(state.getWatermarkAt())) {
                state.setWatermarkAt(advancedWatermark);
            }
        }
        return new EventTimeDecision(status, effectiveWatermark);
    }

    /**
     * Advance an idle route's watermark using wall-clock progress. This is
     * intentionally monotonic and leaves the observed event high-water mark
     * unchanged when the route has not received an event yet.
     */
    public Instant advanceIdle(String eventType, String source, Instant now) {
        Instant candidate = now.minusSeconds(allowedLatenessSeconds);
        repository.ensureIdleExists(eventType, source, candidate, now);
        EventStreamWatermark state = repository.findForUpdate(eventType, source)
            .orElseThrow(() -> new IllegalStateException(
                "Event stream watermark was not created"));
        if (state.getWatermarkAt() == null || candidate.isAfter(state.getWatermarkAt())) {
            state.setWatermarkAt(candidate);
        }
        return state.getWatermarkAt();
    }

    public long allowedLatenessSeconds() {
        return allowedLatenessSeconds;
    }
}
