package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.ReplayActionMode;
import com.tengencorp.tengen.entity.ReplayJob;
import com.tengencorp.tengen.entity.ReplayJobStatus;
import com.tengencorp.tengen.entity.RuleType;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

public record ReplayJobResponse(
        Long id,
        ReplayJobStatus status,
        Long ruleId,
        int ruleRevision,
        int snapshotSchemaVersion,
        String ruleName,
        RuleType ruleType,
        ReplayActionMode actionMode,
        Instant occurredFrom,
        Instant occurredTo,
        Instant warmupFrom,
        Long apiKeyId,
        long totalOutputEvents,
        long totalMaterializedEvents,
        long processedOutputEvents,
        long matchedEvents,
        long errorEvents,
        double progressPercentage,
        Long lastCommittedPosition,
        String createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        String failureCategory,
        String failureMessage) {

    public static ReplayJobResponse from(ReplayJob job, ObjectMapper objectMapper) {
        RuleSnapshot snapshot = objectMapper.convertValue(job.getRuleSnapshot(), RuleSnapshot.class);
        return new ReplayJobResponse(
            job.getId(),
            job.getStatus(),
            job.getRuleId(),
            job.getRuleRevision() != null ? job.getRuleRevision() : 1,
            job.getSnapshotSchemaVersion() != null ? job.getSnapshotSchemaVersion() : 1,
            snapshot.name(),
            snapshot.ruleType(),
            job.getActionMode(),
            job.getOccurredFrom(),
            job.getOccurredTo(),
            job.getWarmupFrom(),
            job.getApiKeyId(),
            job.getTotalOutputEvents(),
            job.getTotalMaterializedEvents(),
            job.getProcessedOutputEvents(),
            job.getMatchedEvents(),
            job.getErrorEvents(),
            progress(job),
            job.getLastCommittedPosition(),
            job.getCreatedBy(),
            job.getCreatedAt(),
            job.getStartedAt(),
            job.getUpdatedAt(),
            job.getCompletedAt(),
            job.getFailureCategory(),
            job.getFailureMessage());
    }

    private static double progress(ReplayJob job) {
        if (job.getStatus() == ReplayJobStatus.COMPLETED) {
            return 100.0;
        }
        if (job.getTotalOutputEvents() <= 0) {
            return 0.0;
        }
        return Math.min(100.0, (job.getProcessedOutputEvents() * 100.0)
            / job.getTotalOutputEvents());
    }
}
