package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationDestination;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe admin representation; credential material is intentionally omitted. */
public record NotificationDestinationResponse(
        Long id,
        String displayName,
        NotificationChannel channel,
        String provider,
        Map<String, Object> configuration,
        boolean credentialConfigured,
        boolean enabled,
        Instant lastTestedAt,
        Boolean lastTestSucceeded,
        String lastTestErrorCategory,
        Instant createdAt,
        Instant updatedAt) {

    public static NotificationDestinationResponse from(NotificationDestination destination) {
        return new NotificationDestinationResponse(
            destination.getId(),
            destination.getDisplayName(),
            destination.getChannel(),
            destination.getProvider(),
            safeConfiguration(destination),
            destination.hasCredentials(),
            destination.isEnabled(),
            destination.getLastTestedAt(),
            destination.getLastTestSucceeded(),
            destination.getLastTestErrorCategory(),
            destination.getCreatedAt(),
            destination.getUpdatedAt());
    }

    /** Only display-safe provider settings are returned to the admin UI. */
    private static Map<String, Object> safeConfiguration(NotificationDestination destination) {
        Map<String, Object> source = destination.getConfiguration();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (source == null) {
            return safe;
        }
        if (destination.getChannel() == NotificationChannel.EMAIL) {
            copy(source, safe, "host");
            copy(source, safe, "port");
            copy(source, safe, "tlsMode");
            copy(source, safe, "fromAddress");
            copy(source, safe, "fromName");
            copy(source, safe, "replyTo");
        } else {
            copy(source, safe, "fromNumber");
        }
        return safe;
    }

    private static void copy(Map<String, Object> source, Map<String, Object> safe, String key) {
        if (source.containsKey(key)) {
            safe.put(key, source.get(key));
        }
    }
}
