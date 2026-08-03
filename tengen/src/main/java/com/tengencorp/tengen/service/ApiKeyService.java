package com.tengencorp.tengen.service;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.entity.ResponseMode;
import com.tengencorp.tengen.repository.ApiKeyRepository;

import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Generates, validates and revokes API keys. Raw keys are prefixed
 * ({@code tg_...}) for recognizability; only their SHA-256 hashes persist.
 */
@Service
public class ApiKeyService {

    private static final String PREFIX = "tg_";
    private static final int RAW_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository repository;

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Create a key and return the raw value (shown once) plus the stored entity.
     */
    @Transactional
    public CreatedKey create(String name, List<String> allowedEventTypes, List<String> allowedSources,
                             Instant expiresAt) {
        return create(name, allowedEventTypes, allowedSources, expiresAt, null);
    }

    @Transactional
    public CreatedKey create(String name, List<String> allowedEventTypes, List<String> allowedSources,
                             Instant expiresAt, ResponseMode responseMode) {
        String raw = PREFIX + HexFormat.of().formatHex(randomBytes());
        String hash = hash(raw);
        String prefix = raw.substring(0, Math.min(raw.length(), 8));

        ApiKey key = new ApiKey();
        key.setName(name);
        key.setKeyHash(hash);
        key.setPrefix(prefix);
        key.setAllowedEventTypes(allowedEventTypes == null || allowedEventTypes.isEmpty() ? null : allowedEventTypes);
        key.setAllowedSources(allowedSources == null || allowedSources.isEmpty() ? null : allowedSources);
        key.setResponseMode(responseMode != null ? responseMode : ResponseMode.COMPACT);
        key.setExpiresAt(expiresAt);
        key.setActive(true);
        repository.save(key);
        return new CreatedKey(raw, key);
    }

    public record CreatedKey(String rawKey, ApiKey key) {
    }

    @Transactional(readOnly = true)
    public ApiKey findByRawKey(String rawKey) {
        return repository.findByKeyHash(hash(rawKey))
            .orElseThrow(() -> new NotFoundException("Invalid API key"));
    }

    @Transactional
    public void revoke(Long id) {
        ApiKey key = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("API key " + id + " not found"));
        key.setActive(false);
        repository.save(key);
    }

    @Transactional(readOnly = true)
    public boolean isValid(String rawKey, Event event) {
        ApiKey key = findByRawKey(rawKey);
        return isValid(key, event);
    }

    public boolean isValid(ApiKey key, Event event) {
        return key.isActive()
            && (key.getExpiresAt() == null || key.getExpiresAt().isAfter(Instant.now()))
            && (key.getAllowedEventTypes() == null || key.getAllowedEventTypes().contains(event.getType()))
            && (key.getAllowedSources() == null || key.getAllowedSources().contains(event.getSource()));
    }

    static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[RAW_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
