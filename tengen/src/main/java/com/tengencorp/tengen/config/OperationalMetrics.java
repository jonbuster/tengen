package com.tengencorp.tengen.config;

import com.tengencorp.tengen.entity.WebhookOutboxStatus;
import com.tengencorp.tengen.repository.WebhookOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Queue gauges needed for readiness dashboards and alerts. */
@Component
public class OperationalMetrics {

    private static final List<WebhookOutboxStatus> ACTIVE = List.of(
        WebhookOutboxStatus.PENDING,
        WebhookOutboxStatus.PROCESSING,
        WebhookOutboxStatus.RETRY_SCHEDULED);

    public OperationalMetrics(MeterRegistry registry, WebhookOutboxRepository repository) {
        Gauge.builder("tengen.webhook.queue.depth", repository,
                value -> value.countByStatusIn(ACTIVE))
            .description("Active webhook deliveries")
            .register(registry);
        Gauge.builder("tengen.webhook.queue.oldest.age.seconds", repository,
                value -> value.findFirstByStatusInOrderByCreatedAtAsc(ACTIVE)
                    .map(row -> Math.max(0, Duration.between(row.getCreatedAt(), Instant.now()).toSeconds()))
                    .orElse(0L))
            .description("Age of the oldest active webhook delivery")
            .register(registry);
    }
}
