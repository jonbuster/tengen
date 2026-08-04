package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.ReplayJobResponse;
import com.tengencorp.tengen.entity.ReplayJob;
import com.tengencorp.tengen.entity.ReplayJobStatus;
import com.tengencorp.tengen.entity.ReplayJobTransition;
import com.tengencorp.tengen.exception.ConflictException;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.repository.ReplayJobRepository;
import com.tengencorp.tengen.repository.ReplayJobTransitionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Applies replay controls under a row lock and records every committed transition. */
@Service
public class ReplayJobControlService {

    private static final Logger log = LoggerFactory.getLogger(ReplayJobControlService.class);

    private final ReplayJobRepository replayJobRepository;
    private final ReplayJobTransitionRepository transitionRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> controlCounters = new ConcurrentHashMap<>();

    public ReplayJobControlService(ReplayJobRepository replayJobRepository,
                                   ReplayJobTransitionRepository transitionRepository,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this.replayJobRepository = replayJobRepository;
        this.transitionRepository = transitionRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ReplayJobResponse pause(Long id, String ifMatch, String actor) {
        ReplayJob job = locked(id);
        assertVersion(job, ifMatch);
        if (job.getStatus() == ReplayJobStatus.PAUSED
                || job.getStatus() == ReplayJobStatus.PAUSE_REQUESTED) {
            return response(job);
        }
        if (job.getStatus() == ReplayJobStatus.QUEUED) {
            job.setPausedAt(Instant.now());
            job.setLeaseToken(null);
            job.setLeaseExpiresAt(null);
            return save(job, ReplayJobStatus.PAUSED, "PAUSED", actor, null);
        }
        if (job.getStatus() == ReplayJobStatus.RUNNING) {
            return save(job, ReplayJobStatus.PAUSE_REQUESTED, "PAUSE_REQUESTED", actor, null);
        }
        throw invalid("pause", job);
    }

    @Transactional
    public ReplayJobResponse resume(Long id, String ifMatch, String actor) {
        ReplayJob job = locked(id);
        assertVersion(job, ifMatch);
        if (job.getStatus() != ReplayJobStatus.PAUSED) {
            throw invalid("resume", job);
        }
        return save(job, ReplayJobStatus.QUEUED, "RESUMED", actor, null);
    }

    @Transactional
    public ReplayJobResponse cancel(Long id, String ifMatch, String actor) {
        ReplayJob job = locked(id);
        assertVersion(job, ifMatch);
        if (job.getStatus() == ReplayJobStatus.CANCELLED
                || job.getStatus() == ReplayJobStatus.CANCEL_REQUESTED) {
            return response(job);
        }
        if (job.getStatus() == ReplayJobStatus.RUNNING
                || job.getStatus() == ReplayJobStatus.PAUSE_REQUESTED) {
            return save(job, ReplayJobStatus.CANCEL_REQUESTED, "CANCEL_REQUESTED", actor, null);
        }
        if (job.getStatus() == ReplayJobStatus.QUEUED
                || job.getStatus() == ReplayJobStatus.PAUSED
                || job.getStatus() == ReplayJobStatus.FAILED) {
            job.setCancelledAt(Instant.now());
            job.setCompletedAt(Instant.now());
            job.setRetryable(false);
            job.setLeaseToken(null);
            job.setLeaseExpiresAt(null);
            return save(job, ReplayJobStatus.CANCELLED, "CANCELLED", actor, null);
        }
        throw invalid("cancel", job);
    }

    @Transactional
    public ReplayJobResponse retry(Long id, String ifMatch, String actor) {
        ReplayJob job = locked(id);
        assertVersion(job, ifMatch);
        if (job.getStatus() != ReplayJobStatus.FAILED) {
            throw invalid("retry", job);
        }
        if (!job.isRetryable()) {
            throw new ConflictException("Replay job " + id + " failed with a non-retryable error");
        }
        job.setLastRetriedAt(Instant.now());
        job.setCompletedAt(null);
        job.setFailureCategory(null);
        job.setFailureMessage(null);
        job.setRetryable(false);
        job.setLeaseToken(null);
        job.setLeaseExpiresAt(null);
        return save(job, ReplayJobStatus.QUEUED, "RETRIED", actor, null);
    }

    private ReplayJobResponse save(ReplayJob job, ReplayJobStatus nextStatus,
                                   String action, String actor, String reason) {
        ReplayJobStatus previous = job.getStatus();
        job.setStatus(nextStatus);
        job = replayJobRepository.saveAndFlush(job);
        appendTransition(job, previous, nextStatus, action, actor, reason);
        registerCommittedAudit(job, previous, nextStatus, action, safeActor(actor));
        return response(job);
    }

    private void registerCommittedAudit(ReplayJob job, ReplayJobStatus previous,
                                        ReplayJobStatus next, String action, String actor) {
        Runnable audit = () -> {
            controlCounters.computeIfAbsent(action, name -> Counter.builder("tengen.replay.controls")
                    .description("Committed replay job controls")
                    .tag("action", name)
                    .register(meterRegistry))
                .increment();
            log.info(
                "event=replay_control name=committed action={} jobId={} fromStatus={} toStatus={} actor={} attempt={}",
                action, job.getId(), previous, next, actor, job.getAttemptCount());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    audit.run();
                }
            });
        } else {
            audit.run();
        }
    }

    private void appendTransition(ReplayJob job, ReplayJobStatus previous,
                                  ReplayJobStatus next, String action,
                                  String actor, String reason) {
        transitionRepository.saveAndFlush(new ReplayJobTransition(
            job.getId(),
            transitionRepository.maxSequence(job.getId()) + 1,
            previous,
            next,
            action,
            safeActor(actor),
            job.getAttemptCount(),
            reason));
    }

    private ReplayJob locked(Long id) {
        return replayJobRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Replay job " + id + " not found"));
    }

    private void assertVersion(ReplayJob job, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match is required for replay job controls");
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            int expected = Integer.parseInt(value);
            int actual = job.getVersion() != null ? job.getVersion() : 0;
            if (expected != actual) {
                throw new ConflictException(
                    "Replay job changed since it was loaded; refresh before trying again");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain a replay job version number");
        }
    }

    private ConflictException invalid(String operation, ReplayJob job) {
        return new ConflictException(
            "Cannot " + operation + " replay job " + job.getId()
                + " while it is " + job.getStatus());
    }

    private ReplayJobResponse response(ReplayJob job) {
        return ReplayJobResponse.from(job, objectMapper);
    }

    private String safeActor(String actor) {
        if (actor == null || actor.isBlank()) {
            return "system";
        }
        return actor.length() <= 100 ? actor : actor.substring(0, 100);
    }
}
