package com.tengencorp.tengen.config;

import com.tengencorp.tengen.repository.ReplayJobJdbcRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Queue depth gauges without rule or job labels. */
@Component
public class ReplayOperationalMetrics {

    public ReplayOperationalMetrics(MeterRegistry registry,
                                    ReplayJobJdbcRepository repository) {
        Gauge.builder("tengen.replay.queue.depth", repository,
                value -> value.countByStatus("QUEUED"))
            .description("Queued replay jobs")
            .register(registry);
        Gauge.builder("tengen.replay.running.depth", repository,
                value -> value.countByStatus("RUNNING"))
            .description("Running replay jobs")
            .register(registry);
        Gauge.builder("tengen.replay.queue.oldest.age.seconds", repository,
                ReplayJobJdbcRepository::oldestQueuedAgeSeconds)
            .description("Age of the oldest queued replay job")
            .register(registry);
    }
}
