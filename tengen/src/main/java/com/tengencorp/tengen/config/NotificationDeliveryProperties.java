package com.tengencorp.tengen.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration for asynchronous email/SMS notification delivery. */
@ConfigurationProperties(prefix = "tengen.notification.worker")
@Validated
@Getter
@Setter
public class NotificationDeliveryProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 1000;
    private long initialDelayMs = 1000;
    @Min(1) private int batchSize = 25;
    @Min(1) private int maxAttempts = 8;
    @Min(1) private long baseDelayMs = 5000;
    @Min(1) private long maxDelayMs = 15 * 60 * 1000L;
    @Min(1) private long leaseDurationMs = 5 * 60 * 1000L;
}
