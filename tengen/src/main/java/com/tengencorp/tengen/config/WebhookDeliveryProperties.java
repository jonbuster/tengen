package com.tengencorp.tengen.config;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the background webhook delivery worker and HTTP client. */
@ConfigurationProperties(prefix = "tengen.webhook.worker")
@Validated
@Getter
@Setter
public class WebhookDeliveryProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 1000;
    private long initialDelayMs = 1000;
    @Min(1) private int batchSize = 25;
    @Min(1) private int maxAttempts = 8;
    @Min(1) private long baseDelayMs = 5000;
    @Min(1) private long maxDelayMs = 15 * 60 * 1000L;
    @Min(1) private long leaseDurationMs = 5 * 60 * 1000L;
    @Min(1) private long connectTimeoutMs = 3000;
    @Min(1) private long readTimeoutMs = 5000;
    @Size(min = 32)
    private String signingSecret = "dev-webhook-signing-secret-change-me";
}
