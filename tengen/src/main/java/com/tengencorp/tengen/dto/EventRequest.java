package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.Event;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

/**
 * Request DTO for POST /api/events. The raw data map is kept as-is and echoed
 * back in the response.
 */
public record EventRequest(
        @NotBlank(message = "type is required") String type,
        @NotBlank(message = "source is required") String source,
        Instant timestamp,
        @NotNull(message = "data is required") Map<String, Object> data) {

    /**
     * Convert to a persisted Event. Falls back to now() when timestamp is absent.
     */
    public Event toEntity() {
        return new Event(type, source, timestamp != null ? timestamp : Instant.now(), data);
    }
}
