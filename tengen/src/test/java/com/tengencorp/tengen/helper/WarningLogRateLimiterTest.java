package com.tengencorp.tengen.helper;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class WarningLogRateLimiterTest {

    @Test
    void suppressesRepeatedWarningsUntilTheWindowExpires() {
        AtomicLong now = new AtomicLong();
        WarningLogRateLimiter limiter = new WarningLogRateLimiter(
            Duration.ofSeconds(60), 10, now::get);

        assertThat(limiter.tryAcquire("rule_failure", "rule-1")).isTrue();
        assertThat(limiter.tryAcquire("rule_failure", "rule-1")).isFalse();

        now.set(Duration.ofSeconds(60).toNanos());
        assertThat(limiter.tryAcquire("rule_failure", "rule-1")).isTrue();
    }

    @Test
    void doesNotGrowBeyondTheConfiguredKeyLimitAndEvictsExpiredEntries() {
        AtomicLong now = new AtomicLong();
        WarningLogRateLimiter limiter = new WarningLogRateLimiter(
            Duration.ofSeconds(60), 2, now::get);

        assertThat(limiter.tryAcquire("category", "first")).isTrue();
        assertThat(limiter.tryAcquire("category", "second")).isTrue();
        assertThat(limiter.tryAcquire("category", "third")).isFalse();

        now.set(Duration.ofSeconds(60).toNanos());
        assertThat(limiter.tryAcquire("category", "third")).isTrue();
    }
}
