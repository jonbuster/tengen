package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.NotificationDeliveryProperties;
import com.tengencorp.tengen.helper.LogSafe;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Polls committed notification rows and submits them outside event ingestion. */
@Service
@ConditionalOnProperty(
    name = "tengen.notification.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NotificationDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    private final NotificationOutboxDeliveryService deliveryService;
    private final NotificationProviderService providerService;
    private final NotificationDeliveryProperties properties;
    private final Counter submitted;
    private final Counter failed;

    public NotificationDeliveryWorker(NotificationOutboxDeliveryService deliveryService,
                                      NotificationProviderService providerService,
                                      NotificationDeliveryProperties properties,
                                      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.deliveryService = deliveryService;
        this.providerService = providerService;
        this.properties = properties;
        this.submitted = meterRegistry.counter("tengen.notification.attempts", "result", "submitted");
        this.failed = meterRegistry.counter("tengen.notification.attempts", "result", "failed");
    }

    @Scheduled(
        fixedDelayString = "${tengen.notification.worker.poll-interval-ms:1000}",
        initialDelayString = "${tengen.notification.worker.initial-delay-ms:1000}")
    public void processDueNotifications() {
        List<NotificationDeliveryAttempt> attempts;
        try {
            attempts = deliveryService.claimBatch(
                Instant.now(), properties.getBatchSize(), properties.getLeaseDurationMs());
        } catch (Exception exception) {
            // Keep worker failures isolated from event ingestion and other workers.
            log.error(
                "event=notification_worker name=claim_failed exceptionType={}",
                LogSafe.exceptionType(exception), exception);
            return;
        }
        for (NotificationDeliveryAttempt attempt : attempts) {
            try {
                deliverOne(attempt);
            } catch (Exception exception) {
                // Leave the lease recoverable; a later poll can reclaim it after expiry.
                log.error(
                    "event=notification_delivery name=unexpected_failure outboxId={} ruleId={} revision={} exceptionType={}",
                    attempt.outboxId(), attempt.ruleId(), attempt.ruleRevision(),
                    LogSafe.exceptionType(exception), exception);
            }
        }
    }

    private void deliverOne(NotificationDeliveryAttempt attempt) {
        var destination = deliveryService.destination(attempt.destinationId());
        NotificationProviderResult result = destination == null
            ? NotificationProviderResult.failure(false, "DESTINATION_NOT_FOUND",
                "Notification destination is no longer available")
            : providerService.submit(destination, toOutboxView(attempt));
        Instant completedAt = Instant.now();
        if (result.successful()) {
            if (deliveryService.markSubmitted(attempt, result, completedAt)) {
                submitted.increment();
            }
            return;
        }
        if (deliveryService.markFailed(attempt, result, completedAt,
                properties.getMaxAttempts(), properties.getBaseDelayMs(), properties.getMaxDelayMs())) {
            failed.increment();
        }
    }

    /** Worker attempts carry the immutable fields required by the provider adapter. */
    private com.tengencorp.tengen.entity.NotificationOutbox toOutboxView(
            NotificationDeliveryAttempt attempt) {
        var outbox = new com.tengencorp.tengen.entity.NotificationOutbox();
        outbox.setId(attempt.outboxId());
        outbox.setDestinationId(attempt.destinationId());
        outbox.setChannel(attempt.channel());
        outbox.setProvider(attempt.provider());
        outbox.setMessageSnapshot(attempt.messageSnapshot());
        return outbox;
    }
}
