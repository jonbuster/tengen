package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.WebhookDeliveryProperties;
import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.helper.WarningLogRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Polls committed outbox rows and performs one delivery attempt per lease. */
@Service
@ConditionalOnProperty(
    name = "tengen.webhook.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final WebhookOutboxDeliveryService deliveryService;
    private final WebhookClient webhookClient;
    private final WebhookDeliveryProperties properties;
    private final Counter delivered;
    private final Counter failed;
    private final WarningLogRateLimiter warningLogRateLimiter = new WarningLogRateLimiter();

    public WebhookDeliveryWorker(WebhookOutboxDeliveryService deliveryService,
                                 WebhookClient webhookClient,
                                 WebhookDeliveryProperties properties,
                                 MeterRegistry meterRegistry) {
        this.deliveryService = deliveryService;
        this.webhookClient = webhookClient;
        this.properties = properties;
        this.delivered = meterRegistry.counter("tengen.webhook.attempts", "result", "delivered");
        this.failed = meterRegistry.counter("tengen.webhook.attempts", "result", "failed");
    }

    @Scheduled(
        fixedDelayString = "${tengen.webhook.worker.poll-interval-ms:1000}",
        initialDelayString = "${tengen.webhook.worker.initial-delay-ms:1000}")
    public void processDueDeliveries() {
        List<WebhookDeliveryAttempt> attempts;
        try {
            Instant claimedAt = Instant.now();
            attempts = deliveryService.claimBatch(
                claimedAt,
                properties.getBatchSize(),
                properties.getLeaseDurationMs());
        } catch (Exception e) {
            log.error("event=webhook_worker name=claim_failed exceptionType={}",
                LogSafe.exceptionType(e), e);
            return;
        }
        if (attempts.isEmpty()) {
            return;
        }

        log.debug("event=webhook_worker name=batch_claimed count={}", attempts.size());
        for (WebhookDeliveryAttempt attempt : attempts) {
            try {
                deliverOne(attempt);
            } catch (Exception e) {
                // Leave the lease recoverable; a later poll will reclaim it after expiry.
                log.error(
                    "event=webhook_delivery name=unexpected_failure outboxId={} ruleId={} revision={} exceptionType={}",
                    attempt.outboxId(), attempt.ruleId(), attempt.ruleRevision(),
                    LogSafe.exceptionType(e), e);
            }
        }
    }

    private void deliverOne(WebhookDeliveryAttempt attempt) {
        WebhookDeliveryResult result = webhookClient.deliverOnce(
            attempt.callbackUrl(), attempt.payload(), attempt.outboxId(), attempt.createdAt());
        Instant completedAt = Instant.now();

        if (result.successful()) {
            boolean finalized = deliveryService.markDelivered(attempt, result, completedAt);
            if (finalized) {
                delivered.increment();
                log.debug(
                    "event=webhook_delivery name=succeeded outboxId={} ruleId={} revision={} attempt={} status={} durationMs={}",
                    attempt.outboxId(), attempt.ruleId(), attempt.ruleRevision(), attempt.attemptNumber(),
                    result.statusCode(), result.durationMs());
            } else {
                warnFinalizeSkipped(attempt, "delivered");
            }
            return;
        }

        boolean finalized = deliveryService.markFailed(
            attempt,
            result,
            completedAt,
            properties.getMaxAttempts(),
            properties.getBaseDelayMs(),
            properties.getMaxDelayMs());
        if (finalized) {
            failed.increment();
            warn("webhook_delivery_failed", String.valueOf(attempt.outboxId()),
                "event=webhook_delivery name=failed outboxId={} ruleId={} revision={} attempt={} retryable={} status={} outcome={} durationMs={}",
                attempt.outboxId(), attempt.ruleId(), attempt.ruleRevision(), attempt.attemptNumber(),
                result.retryable(), result.statusCode(), failureCategory(result), result.durationMs());
        } else {
            warnFinalizeSkipped(attempt, "failed");
        }
    }

    private String failureCategory(WebhookDeliveryResult result) {
        if (result.statusCode() != null) {
            return result.retryable() ? "HTTP_RETRYABLE" : "HTTP_PERMANENT";
        }
        return result.retryable() ? "TRANSPORT_RETRYABLE" : "CLIENT_ERROR";
    }

    private void warnFinalizeSkipped(WebhookDeliveryAttempt attempt, String outcome) {
        warn("webhook_finalize_skipped", String.valueOf(attempt.outboxId()),
            "event=webhook_delivery name=finalize_skipped outboxId={} ruleId={} revision={} outcome={}",
            attempt.outboxId(), attempt.ruleId(), attempt.ruleRevision(), outcome);
    }

    private void warn(String category, String stableKey, String message, Object... arguments) {
        if (warningLogRateLimiter.tryAcquire(category, stableKey)) {
            log.warn(message, arguments);
        }
    }
}
