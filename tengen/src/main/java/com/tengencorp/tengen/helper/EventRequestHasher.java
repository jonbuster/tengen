package com.tengencorp.tengen.helper;

import com.tengencorp.tengen.dto.EventRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Creates a stable fingerprint for an event request's semantic payload. */
@Component
public class EventRequestHasher {

    private final ObjectMapper objectMapper;

    public EventRequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(EventRequest request) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("data", canonicalize(request.data()));
        fields.put("source", request.source());
        fields.put("timestamp", request.timestamp());
        fields.put("type", request.type());

        try {
            byte[] canonicalJson = objectMapper.writeValueAsString(fields)
                .getBytes(StandardCharsets.UTF_8);
            return hexDigest(canonicalJson);
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint event request", e);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nestedValue) -> sorted.put(String.valueOf(key), canonicalize(nestedValue)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> canonical = new ArrayList<>(list.size());
            list.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        return value;
    }

    private String hexDigest(byte[] value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
