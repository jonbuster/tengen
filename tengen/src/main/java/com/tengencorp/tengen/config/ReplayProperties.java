package com.tengencorp.tengen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded replay storage and worker settings. */
@ConfigurationProperties(prefix = "tengen.replay")
public class ReplayProperties {

    private long maxRangeDays = 31;
    private long maxMaterializedOutputEvents = 10_000;
    private final Worker worker = new Worker();

    public long getMaxRangeDays() {
        return maxRangeDays;
    }

    public void setMaxRangeDays(long maxRangeDays) {
        this.maxRangeDays = maxRangeDays;
    }

    public long getMaxMaterializedOutputEvents() {
        return maxMaterializedOutputEvents;
    }

    public void setMaxMaterializedOutputEvents(long maxMaterializedOutputEvents) {
        this.maxMaterializedOutputEvents = maxMaterializedOutputEvents;
    }

    public Worker getWorker() {
        return worker;
    }

    public static class Worker {

        private boolean enabled = true;
        private long pollIntervalMs = 1_000;
        private long initialDelayMs = 1_000;
        private int batchSize = 100;
        private long leaseDurationMs = 300_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getLeaseDurationMs() {
            return leaseDurationMs;
        }

        public void setLeaseDurationMs(long leaseDurationMs) {
            this.leaseDurationMs = leaseDurationMs;
        }
    }
}
