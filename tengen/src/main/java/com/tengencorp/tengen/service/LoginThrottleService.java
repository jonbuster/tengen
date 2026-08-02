package com.tengencorp.tengen.service;

import com.tengencorp.tengen.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Small single-instance login throttle for the single-organization deployment. */
@Service
public class LoginThrottleService {

    private final ConcurrentHashMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final long windowSeconds;
    private final Clock clock;

    @Autowired
    public LoginThrottleService(
            @Value("${tengen.auth.login-max-attempts:5}") int maxAttempts,
            @Value("${tengen.auth.login-window-seconds:60}") long windowSeconds) {
        this(maxAttempts, windowSeconds, Clock.systemUTC());
    }

    LoginThrottleService(int maxAttempts, long windowSeconds, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
    }

    public void check(String remoteAddress, String username) {
        String key = key(remoteAddress, username);
        AttemptWindow window = attempts.get(key);
        Instant now = clock.instant();
        if (window != null && window.expiresAt().isAfter(now) && window.count() >= maxAttempts) {
            throw new TooManyRequestsException("Too many login attempts; try again later");
        }
    }

    public void failure(String remoteAddress, String username) {
        String key = key(remoteAddress, username);
        Instant now = clock.instant();
        attempts.compute(key, (ignored, existing) -> {
            if (existing == null || !existing.expiresAt().isAfter(now)) {
                return new AttemptWindow(1, now.plusSeconds(windowSeconds));
            }
            return new AttemptWindow(existing.count() + 1, existing.expiresAt());
        });
    }

    public void success(String remoteAddress, String username) {
        attempts.remove(key(remoteAddress, username));
    }

    private String key(String remoteAddress, String username) {
        return remoteAddress + ":" + username.toLowerCase(Locale.ROOT);
    }

    private record AttemptWindow(int count, Instant expiresAt) {
    }
}
