package com.tengencorp.tengen.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;

/** Per-key fixed-window limiter for the initial single-instance deployment. */
@Service
public class ApiKeyRateLimiter {

    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;

    public ApiKeyRateLimiter(
            @Value("${tengen.ingestion.rate-limit-per-minute:600}") int limitPerMinute) {
        if (limitPerMinute < 1) {
            throw new IllegalArgumentException("Ingestion rate limit must be positive");
        }
        this.limitPerMinute = limitPerMinute;
    }

    public boolean tryAcquire(Long keyId) {
        Instant now = Instant.now();
        Window result = windows.compute(keyId, (ignored, existing) -> {
            Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
            if (existing == null || !existing.minute().equals(minute)) {
                return new Window(minute, 1);
            }
            return new Window(existing.minute(), existing.count() + 1);
        });
        return result.count() <= limitPerMinute;
    }

    private record Window(Instant minute, int count) {
    }
}
