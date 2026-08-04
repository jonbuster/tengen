package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.EventStreamWatermark;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EventStreamWatermarkRepository extends JpaRepository<EventStreamWatermark, Long> {

    @Modifying
    @Query(value = """
        insert into event_stream_watermarks
            (event_type, source, max_occurred_at, watermark_at, created_at, updated_at)
        values (:eventType, :source, :maxOccurredAt, :watermarkAt, :now, :now)
        on conflict (source, event_type) do nothing
        """, nativeQuery = true)
    int ensureExists(@Param("eventType") String eventType,
                     @Param("source") String source,
                     @Param("maxOccurredAt") Instant maxOccurredAt,
                     @Param("watermarkAt") Instant watermarkAt,
                     @Param("now") Instant now);

    @Modifying
    @Query(value = """
        insert into event_stream_watermarks
            (event_type, source, max_occurred_at, watermark_at, created_at, updated_at)
        values (:eventType, :source, null, :watermarkAt, :now, :now)
        on conflict (source, event_type) do nothing
        """, nativeQuery = true)
    int ensureIdleExists(@Param("eventType") String eventType,
                         @Param("source") String source,
                         @Param("watermarkAt") Instant watermarkAt,
                         @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select state from EventStreamWatermark state
        where state.eventType = :eventType
          and state.source = :source
        """)
    Optional<EventStreamWatermark> findForUpdate(@Param("eventType") String eventType,
                                                  @Param("source") String source);
}
