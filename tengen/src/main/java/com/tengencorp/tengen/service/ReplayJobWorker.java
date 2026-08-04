package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.ReplayProperties;
import com.tengencorp.tengen.repository.ReplayJobJdbcRepository;
import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.helper.WarningLogRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Leased, restartable worker for queued replay jobs. */
@Service
@ConditionalOnProperty(name = "tengen.replay.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ReplayJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ReplayJobWorker.class);

    private final ReplayJobJdbcRepository jdbcRepository;
    private final ReplayJobBatchService batchService;
    private final ReplayProperties properties;
    private final Counter jobsCompleted;
    private final Counter jobsFailed;
    private final Counter matchedResults;
    private final Counter evaluationErrors;
    private final Counter leasesRecovered;
    private final WarningLogRateLimiter warningLogRateLimiter = new WarningLogRateLimiter();

    public ReplayJobWorker(ReplayJobJdbcRepository jdbcRepository,
                           ReplayJobBatchService batchService,
                           ReplayProperties properties,
                           MeterRegistry meterRegistry) {
        if (properties.getWorker().getBatchSize() < 1
                || properties.getWorker().getLeaseDurationMs() < 1
                || properties.getWorker().getPollIntervalMs() < 1
                || properties.getWorker().getInitialDelayMs() < 0) {
            throw new IllegalArgumentException("Replay worker settings must be positive");
        }
        this.jdbcRepository = jdbcRepository;
        this.batchService = batchService;
        this.properties = properties;
        this.jobsCompleted = Counter.builder("tengen.replay.jobs.completed")
            .description("Replay jobs completed")
            .register(meterRegistry);
        this.jobsFailed = Counter.builder("tengen.replay.jobs.failed")
            .description("Replay jobs failed")
            .register(meterRegistry);
        this.matchedResults = Counter.builder("tengen.replay.results.matched")
            .description("Matched replay outcomes")
            .register(meterRegistry);
        this.evaluationErrors = Counter.builder("tengen.replay.results.evaluation.errors")
            .description("Replay outcomes with evaluation errors")
            .register(meterRegistry);
        this.leasesRecovered = Counter.builder("tengen.replay.leases.recovered")
            .description("Replay leases recovered after expiry")
            .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString = "${tengen.replay.worker.poll-interval-ms:1000}",
        initialDelayString = "${tengen.replay.worker.initial-delay-ms:1000}")
    public void processQueuedJobs() {
        runOnce();
    }

    public void runOnce() {
        int recoveredControls = jdbcRepository.recoverExpiredRequestedControls();
        leasesRecovered.increment(recoveredControls);
        if (recoveredControls > 0) {
            log.info("event=replay_worker name=requested_controls_recovered count={}", recoveredControls);
        }
        Optional<ReplayJobJdbcRepository.ReplayJobLease> claimed = jdbcRepository.claimOldest(
            properties.getWorker().getLeaseDurationMs());
        if (claimed.isEmpty()) {
            return;
        }

        ReplayJobJdbcRepository.ReplayJobLease lease = claimed.get();
        if (lease.recovered()) {
            leasesRecovered.increment();
            warn("replay_lease_recovered", String.valueOf(lease.jobId()),
                "event=replay_worker name=lease_recovered jobId={} reason=expired_lease", lease.jobId());
        }
        try {
            while (true) {
                ReplayJobBatchService.ReplayBatchResult result = batchService.processBatch(
                    lease.jobId(), lease.leaseToken());
                matchedResults.increment(result.matchedEvents());
                evaluationErrors.increment(result.errorEvents());
                if (result.controlRequested()) {
                    jdbcRepository.applyRequestedControl(lease.jobId(), lease.leaseToken());
                    return;
                }
                if (result.completed() || !result.progressMade()) {
                    if (result.completed()) {
                        jobsCompleted.increment();
                        log.info("event=replay_worker name=job_completed jobId={} matchedCount={} errorCount={}",
                            lease.jobId(), result.matchedEvents(), result.errorEvents());
                    }
                    return;
                }
                if (jdbcRepository.applyRequestedControl(lease.jobId(), lease.leaseToken())) {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            if (jdbcRepository.markFailed(
                lease.jobId(), lease.leaseToken(), "WORKER_ERROR", safeFailure(exception))) {
                jobsFailed.increment();
                log.error(
                    "event=replay_worker name=job_failed jobId={} category=WORKER_ERROR exceptionType={}",
                    lease.jobId(), LogSafe.exceptionType(exception), exception);
            } else {
                warn("replay_failure_finalize_skipped", String.valueOf(lease.jobId()),
                    "event=replay_worker name=failure_finalize_skipped jobId={} category=WORKER_ERROR",
                    lease.jobId());
            }
        }
    }

    private void warn(String category, String stableKey, String message, Object... arguments) {
        if (warningLogRateLimiter.tryAcquire(category, stableKey)) {
            log.warn(message, arguments);
        }
    }

    private String safeFailure(RuntimeException exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName();
        return name.length() <= 1000 ? name : name.substring(0, 1000);
    }
}
