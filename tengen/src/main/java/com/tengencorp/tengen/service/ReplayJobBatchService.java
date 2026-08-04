package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.ReplayProperties;
import com.tengencorp.tengen.dto.RuleSnapshot;
import com.tengencorp.tengen.repository.ReplayJobJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/** Commits one replay checkpoint atomically with its state and outcomes. */
@Service
public class ReplayJobBatchService {

    private final ReplayJobJdbcRepository jdbcRepository;
    private final ReplayEvaluator evaluator;
    private final ReplayProperties properties;
    private final ObjectMapper objectMapper;

    public ReplayJobBatchService(ReplayJobJdbcRepository jdbcRepository,
                                 ReplayEvaluator evaluator,
                                 ReplayProperties properties,
                                 ObjectMapper objectMapper) {
        this.jdbcRepository = jdbcRepository;
        this.evaluator = evaluator;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReplayBatchResult processBatch(Long jobId, String leaseToken) {
        ReplayJobJdbcRepository.ReplayWorkerJob job = jdbcRepository
            .findWorkerJob(jobId, leaseToken)
            .orElseThrow(() -> new IllegalStateException("Replay job lease is no longer owned"));
        if (job.status() != com.tengencorp.tengen.entity.ReplayJobStatus.RUNNING) {
            return new ReplayBatchResult(false, false, 0, 0, true);
        }
        RuleSnapshot snapshot = objectMapper.convertValue(job.ruleSnapshot(), RuleSnapshot.class);
        long afterPosition = job.lastCommittedPosition() != null
            ? job.lastCommittedPosition() : 0;
        List<ReplayJobJdbcRepository.ReplayInput> inputs = jdbcRepository.findInputs(
            jobId, afterPosition, properties.getWorker().getBatchSize());
        if (inputs.isEmpty()) {
            boolean completed = afterPosition >= job.totalMaterializedEvents();
            if (completed) {
                ReplayJobJdbcRepository.ReplayProgressUpdate progress = jdbcRepository.updateProgress(
                    jobId, leaseToken, afterPosition, job.processedOutputEvents(),
                    job.matchedEvents(), job.errorEvents(), true,
                    properties.getWorker().getLeaseDurationMs());
                return new ReplayBatchResult(
                    progress.status() == com.tengencorp.tengen.entity.ReplayJobStatus.COMPLETED,
                    false, 0, 0, isControlRequest(progress.status()));
            }
            return new ReplayBatchResult(false, false, 0, 0, false);
        }

        long processedOutputEvents = job.processedOutputEvents();
        long matchedEvents = job.matchedEvents();
        long errorEvents = job.errorEvents();
        long batchMatched = 0;
        long batchErrors = 0;
        long lastPosition = afterPosition;

        for (ReplayJobJdbcRepository.ReplayInput input : inputs) {
            ReplayEvaluator.ReplayEvaluation evaluation = evaluator.evaluate(jobId, snapshot, input);
            if (input.inRequestedRange()) {
                Instant completedAt = Instant.now();
                int inserted = jdbcRepository.insertOutcome(
                    new ReplayJobJdbcRepository.ReplayOutcomeInsert(
                        jobId,
                        input.position(),
                        input.originalEventId(),
                        input.type(),
                        input.source(),
                        input.occurredAt(),
                        evaluation.matched(),
                        evaluation.groupKey(),
                        evaluation.aggregate(),
                        evaluation.errorCategory(),
                        completedAt));
                if (inserted == 1) {
                    processedOutputEvents++;
                    if (evaluation.matched()) {
                        matchedEvents++;
                        batchMatched++;
                    }
                    if (evaluation.errorCategory() != null) {
                        errorEvents++;
                        batchErrors++;
                    }
                }
            }
            lastPosition = input.position();
        }

        boolean completed = lastPosition >= job.totalMaterializedEvents();
        ReplayJobJdbcRepository.ReplayProgressUpdate progress = jdbcRepository.updateProgress(
            jobId, leaseToken, lastPosition, processedOutputEvents, matchedEvents,
            errorEvents, completed, properties.getWorker().getLeaseDurationMs());
        return new ReplayBatchResult(
            progress.status() == com.tengencorp.tengen.entity.ReplayJobStatus.COMPLETED,
            true,
            batchMatched,
            batchErrors,
            isControlRequest(progress.status()));
    }

    public record ReplayBatchResult(boolean completed, boolean progressMade,
                                    long matchedEvents, long errorEvents,
                                    boolean controlRequested) {
    }

    private boolean isControlRequest(com.tengencorp.tengen.entity.ReplayJobStatus status) {
        return status == com.tengencorp.tengen.entity.ReplayJobStatus.PAUSE_REQUESTED
            || status == com.tengencorp.tengen.entity.ReplayJobStatus.CANCEL_REQUESTED;
    }
}
