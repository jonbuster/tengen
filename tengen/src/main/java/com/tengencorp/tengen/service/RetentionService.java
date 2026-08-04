package com.tengencorp.tengen.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.tengencorp.tengen.helper.LogSafe;
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
        try {
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
            deleted.put("rabbitmq_message_receipts", drain("""
                DELETE FROM rabbitmq_message_receipts WHERE id IN (
                    SELECT id FROM rabbitmq_message_receipts
                    WHERE processed_at < ? LIMIT ?
                )
                """, cutoff));
            deleted.put("webhook_outbox", drain("""
                DELETE FROM webhook_outbox WHERE id IN (
                    SELECT id FROM webhook_outbox
                    WHERE status IN ('DELIVERED', 'DEAD_LETTER') AND created_at < ? LIMIT ?
                )
                """, cutoff));
            deleted.put("replay_jobs", drain("""
                DELETE FROM replay_jobs WHERE id IN (
                    SELECT id FROM replay_jobs
                    WHERE status IN ('COMPLETED', 'CANCELLED')
                      AND COALESCE(completed_at, updated_at) < ?
                    LIMIT ?
                )
                """, cutoff));
            deleted.put("rule_events", drain("""
                DELETE FROM rule_events WHERE id IN (
                    SELECT id FROM rule_events WHERE occurred_at < ? LIMIT ?
                )
                """, cutoff));
            deleted.put("rule_sequence_instance_events", drain("""
                DELETE FROM rule_sequence_instance_events WHERE id IN (
                    SELECT instance_event.id
                    FROM rule_sequence_instance_events instance_event
                    JOIN rule_sequence_instances instance ON instance.id = instance_event.instance_id
                    WHERE instance.status IN ('COMPLETED', 'CANCELLED')
                      AND instance.updated_at < ?
                    LIMIT ?
                )
                """, cutoff));
            deleted.put("rule_sequence_instances", drain("""
                DELETE FROM rule_sequence_instances WHERE id IN (
                    SELECT id FROM rule_sequence_instances
                    WHERE status IN ('COMPLETED', 'CANCELLED')
                      AND updated_at < ? LIMIT ?
                )
                """, cutoff));
            deleted.put("rule_absence_instances", drain("""
                DELETE FROM rule_absence_instances WHERE id IN (
                    SELECT id FROM rule_absence_instances
                    WHERE status IN ('SATISFIED', 'TRIGGERED', 'CANCELLED')
                      AND updated_at < ? LIMIT ?
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
                      AND NOT EXISTS (
                          SELECT 1 FROM rule_sequence_instance_events sequence_event
                          WHERE sequence_event.event_id = event.id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM rule_absence_instances absence_instance
                          WHERE absence_instance.start_event_id = event.id
                            AND absence_instance.status = 'PENDING'
                      )
                      AND NOT EXISTS (SELECT 1 FROM webhook_outbox outbox WHERE outbox.event_id = event.id)
                      AND NOT EXISTS (SELECT 1 FROM event_idempotency idem WHERE idem.event_id = event.id)
                    LIMIT ?
                )
                """, cutoff));
            int total = deleted.values().stream().mapToInt(Integer::intValue).sum();
            deletedCounter.increment(total);
            if (total > 0) {
                log.info("event=retention name=cleanup_completed cutoff={} deleted={}", cutoff, deleted);
            }
        } catch (Exception exception) {
            int partialTotal = deleted.values().stream().mapToInt(Integer::intValue).sum();
            if (partialTotal > 0) {
                deletedCounter.increment(partialTotal);
            }
            log.error(
                "event=retention name=cleanup_failed cutoff={} deleted={} partialTotal={} exceptionType={}",
                cutoff, deleted, partialTotal, LogSafe.exceptionType(exception), exception);
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
