package com.tengencorp.tengen.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the background webhook delivery worker and HTTP client. */
@ConfigurationProperties(prefix = "tengen.webhook.worker")
@Getter
@Setter
public class WebhookDeliveryProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 1000;
    private long initialDelayMs = 1000;
    private int batchSize = 25;
    private int maxAttempts = 8;
    private long baseDelayMs = 5000;
    private long maxDelayMs = 15 * 60 * 1000L;
    private long leaseDurationMs = 5 * 60 * 1000L;
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 5000;
}
