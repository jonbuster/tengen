package com.tengencorp.tengen.dto;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.entity.ResponseMode;
import com.tengencorp.tengen.service.ApiKeyService;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO. {@code rawKey} is populated only when the key is created.
 */
public record ApiKeyResponse(
        Long id,
        String name,
        String prefix,
        List<String> allowedEventTypes,
        List<String> allowedSources,
        ResponseMode responseMode,
        boolean active,
        Instant expiresAt,
        Instant createdAt,
        String rawKey) {

    /** Backward-compatible constructor for callers before response modes existed. */
    public ApiKeyResponse(Long id, String name, String prefix, List<String> allowedEventTypes,
                          List<String> allowedSources, boolean active, Instant expiresAt,
                          Instant createdAt, String rawKey) {
        this(id, name, prefix, allowedEventTypes, allowedSources, ResponseMode.COMPACT,
            active, expiresAt, createdAt, rawKey);
    }

    public static ApiKeyResponse from(ApiKey key) {
        return new ApiKeyResponse(
            key.getId(),
            key.getName(),
            key.getPrefix(),
            key.getAllowedEventTypes(),
            key.getAllowedSources(),
            key.getEffectiveResponseMode(),
            key.isActive(),
            key.getExpiresAt(),
            key.getCreatedAt(),
            null);
    }

    public static ApiKeyResponse created(ApiKeyService.CreatedKey created) {
        ApiKey key = created.key();
        return new ApiKeyResponse(
            key.getId(),
            key.getName(),
            key.getPrefix(),
            key.getAllowedEventTypes(),
            key.getAllowedSources(),
            key.getEffectiveResponseMode(),
            key.isActive(),
            key.getExpiresAt(),
            key.getCreatedAt(),
            created.rawKey());
    }
}
