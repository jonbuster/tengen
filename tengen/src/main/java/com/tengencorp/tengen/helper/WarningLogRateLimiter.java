package com.tengencorp.tengen.helper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded per-category warning coalescing. Metrics remain responsible for
 * counting every occurrence; this class only limits repeated log lines.
 */
public final class WarningLogRateLimiter {

    private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);
    private static final int DEFAULT_MAX_KEYS = 2_048;

    private final long windowNanos;
    private final int maxKeys;
    private final LongSupplier nanoTime;
    private final Map<String, Long> lastLoggedAt = new HashMap<>();

    public WarningLogRateLimiter() {
        this(DEFAULT_WINDOW, DEFAULT_MAX_KEYS, System::nanoTime);
    }

    WarningLogRateLimiter(Duration window, int maxKeys, LongSupplier nanoTime) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Warning log window must be positive");
        }
        if (maxKeys < 1) {
            throw new IllegalArgumentException("Warning log cache size must be positive");
        }
        this.windowNanos = window.toNanos();
        this.maxKeys = maxKeys;
        this.nanoTime = nanoTime;
    }

    /** Returns true when a warning for this stable category/key may be emitted. */
    public synchronized boolean tryAcquire(String category, String stableKey) {
        String key = LogSafe.text(category) + ':' + LogSafe.text(stableKey);
        long now = nanoTime.getAsLong();
        Long previous = lastLoggedAt.get(key);
        if (previous != null && now - previous < windowNanos) {
            return false;
        }

        if (previous == null && lastLoggedAt.size() >= maxKeys) {
            evictExpired(now);
            if (lastLoggedAt.size() >= maxKeys) {
                return false;
            }
        }
        lastLoggedAt.put(key, now);
        return true;
    }

    private void evictExpired(long now) {
        lastLoggedAt.entrySet().removeIf(entry -> now - entry.getValue() >= windowNanos);
    }
}
