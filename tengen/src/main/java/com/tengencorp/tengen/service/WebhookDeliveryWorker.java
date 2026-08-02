package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.WebhookDeliveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

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

    public WebhookDeliveryWorker(WebhookOutboxDeliveryService deliveryService,
                                 WebhookClient webhookClient,
                                 WebhookDeliveryProperties properties) {
        this.deliveryService = deliveryService;
        this.webhookClient = webhookClient;
        this.properties = properties;
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
            log.error("Could not claim webhook outbox deliveries", e);
            return;
        }
        if (attempts.isEmpty()) {
            return;
        }

        log.debug("Claimed {} webhook outbox deliveries", attempts.size());
        for (WebhookDeliveryAttempt attempt : attempts) {
            try {
                deliverOne(attempt);
            } catch (Exception e) {
                // Leave the lease recoverable; a later poll will reclaim it after expiry.
                log.error("Webhook outbox attempt failed unexpectedly: outboxId={}, rule={}",
                    attempt.outboxId(), attempt.ruleName(), e);
            }
        }
    }

    private void deliverOne(WebhookDeliveryAttempt attempt) {
        WebhookDeliveryResult result = webhookClient.deliverOnce(
            attempt.callbackUrl(), attempt.payload());
        Instant completedAt = Instant.now();

        if (result.successful()) {
            boolean finalized = deliveryService.markDelivered(attempt, result, completedAt);
            if (finalized) {
                log.info("Webhook outbox delivery succeeded: outboxId={}, rule={}, attempt={}, durationMs={}",
                    attempt.outboxId(), attempt.ruleName(), attempt.attemptNumber(), result.durationMs());
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
            log.warn("Webhook outbox delivery failed: outboxId={}, rule={}, attempt={}, retryable={}, status={}",
                attempt.outboxId(), attempt.ruleName(), attempt.attemptNumber(),
                result.retryable(), result.statusCode());
        }
    }
}
