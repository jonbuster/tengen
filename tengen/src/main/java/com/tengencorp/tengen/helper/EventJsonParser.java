package com.tengencorp.tengen.helper;

import com.tengencorp.tengen.entity.Event;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * Parses raw event JSON into an {@link Event}, shared by the admin test
 * endpoint (previously embedded in the MVC controller).
 */
@Component
public class EventJsonParser {

    private final ObjectMapper objectMapper;

    public EventJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Event parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String type = root.path("type").asText();
            String source = root.path("source").asText();
            String timestampText = root.path("timestamp").asText(null);
            Instant timestamp = (timestampText != null && !timestampText.isBlank())
                ? Instant.parse(timestampText)
                : Instant.now();
            Map<String, Object> data = objectMapper.convertValue(root.path("data"), Map.class);
            return new Event(type, source, timestamp, data);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid event JSON: " + e.getMessage(), e);
        }
    }
}
