package com.tengencorp.tengen.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tengencorp.tengen.config.WebhookDeliveryProperties;
import com.tengencorp.tengen.entity.TriggerMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryWorkerLoggingTest {

    @Test
    void successfulDeliveryIsLoggedAtDebugWithoutTheRuleName() {
        WebhookDeliveryAttempt attempt = new WebhookDeliveryAttempt(
            7L,
            "lease-token",
            11L,
            3,
            "secret-rule-name",
            "https://example.com/webhook",
            Map.of("event", "safe-summary"),
            null,
            TriggerMode.EVERY_MATCH,
            null,
            null,
            Instant.parse("2026-08-04T00:00:00Z"),
            1);

        WebhookOutboxDeliveryService deliveryService = new WebhookOutboxDeliveryService(null, null, null) {
            @Override
            public List<WebhookDeliveryAttempt> claimBatch(Instant now, int batchSize,
                                                            long leaseDurationMs) {
                return List.of(attempt);
            }

            @Override
            public boolean markDelivered(WebhookDeliveryAttempt ignored,
                                         WebhookDeliveryResult result,
                                         Instant deliveredAt) {
                return true;
            }
        };
        WebhookDeliveryProperties properties = new WebhookDeliveryProperties();
        WebhookClient client = new WebhookClient(
            new ObjectMapper(), properties, new WebhookDestinationValidator()) {
            @Override
            public WebhookDeliveryResult deliverOnce(String callbackUrl, Map<String, Object> payload,
                                                      Long deliveryId, Instant createdAt) {
                return WebhookDeliveryResult.success(200, 4);
            }
        };
        WebhookDeliveryWorker worker = new WebhookDeliveryWorker(
            deliveryService, client, properties, new SimpleMeterRegistry());

        Logger logger = (Logger) LoggerFactory.getLogger(WebhookDeliveryWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            worker.processDueDeliveries();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        ILoggingEvent success = appender.list.stream()
            .filter(event -> event.getFormattedMessage().contains("name=succeeded"))
            .findFirst()
            .orElseThrow();
        assertThat(success.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(success.getFormattedMessage()).contains("outboxId=7", "ruleId=11", "revision=3")
            .doesNotContain("secret-rule-name");
    }
}
