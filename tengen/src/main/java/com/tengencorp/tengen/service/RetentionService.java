package com.tengencorp.tengen.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded cleanup for terminal operational data; immutable rule history is excluded. */
@Service
@ConditionalOnProperty(name = "tengen.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;
    private final int batchSize;
    private final Counter deletedCounter;

    public RetentionService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
                            @Value("${tengen.retention.days:90}") int retentionDays,
                            @Value("${tengen.retention.batch-size:1000}") int batchSize) {
        if (retentionDays < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Retention days and batch size must be positive");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.deletedCounter = Counter.builder("tengen.retention.deleted")
            .description("Rows deleted by retention")
            .tag("policy", "operational")
            .register(meterRegistry);
    }

    @Scheduled(cron = "${tengen.retention.schedule:0 15 3 * * *}", zone = "UTC")
    public void enforceRetention() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("refresh_sessions", drain("""
            DELETE FROM refresh_sessions WHERE token_id IN (
                SELECT token_id FROM refresh_sessions WHERE expires_at < ? LIMIT ?
            )
            """, cutoff));
        deleted.put("event_idempotency", drain("""
            DELETE FROM event_idempotency WHERE id IN (
                SELECT id FROM event_idempotency
                WHERE status = 'COMPLETED' AND completed_at < ? LIMIT ?
            )
            """, cutoff));
        deleted.put("webhook_outbox", drain("""
            DELETE FROM webhook_outbox WHERE id IN (
                SELECT id FROM webhook_outbox
                WHERE status IN ('DELIVERED', 'DEAD_LETTER') AND created_at < ? LIMIT ?
            )
            """, cutoff));
        deleted.put("rule_events", drain("""
            DELETE FROM rule_events WHERE id IN (
                SELECT id FROM rule_events WHERE occurred_at < ? LIMIT ?
            )
            """, cutoff));
        deleted.put("rule_action_windows", drain("""
            DELETE FROM rule_action_windows WHERE id IN (
                SELECT id FROM rule_action_windows
                WHERE delivered_at IS NOT NULL AND delivered_at < ? LIMIT ?
            )
            """, cutoff));
        deleted.put("events", drain("""
            DELETE FROM events WHERE id IN (
                SELECT event.id FROM events event
                WHERE event.received_at < ?
                  AND NOT EXISTS (SELECT 1 FROM rule_events re WHERE re.event_id = event.id)
                  AND NOT EXISTS (SELECT 1 FROM webhook_outbox outbox WHERE outbox.event_id = event.id)
                  AND NOT EXISTS (SELECT 1 FROM event_idempotency idem WHERE idem.event_id = event.id)
                LIMIT ?
            )
            """, cutoff));
        int total = deleted.values().stream().mapToInt(Integer::intValue).sum();
        deletedCounter.increment(total);
        if (total > 0) {
            log.info("Retention cleanup completed: cutoff={}, deleted={}", cutoff, deleted);
        }
    }

    private int drain(String sql, Instant cutoff) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int changed = jdbcTemplate.update(sql, Timestamp.from(cutoff), batchSize);
            total += changed;
            if (changed < batchSize) {
                break;
            }
        }
        return total;
    }
}
