package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.ReplayProperties;
import com.tengencorp.tengen.dto.ReplayJobCreateRequest;
import com.tengencorp.tengen.dto.ReplayJobOutcomePage;
import com.tengencorp.tengen.dto.ReplayJobOutcomeResponse;
import com.tengencorp.tengen.dto.ReplayJobResponse;
import com.tengencorp.tengen.dto.RuleSnapshot;
import com.tengencorp.tengen.entity.ReplayActionMode;
import com.tengencorp.tengen.entity.ReplayJob;
import com.tengencorp.tengen.entity.ReplayJobStatus;
import com.tengencorp.tengen.entity.RuleRevision;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.repository.ApiKeyRepository;
import com.tengencorp.tengen.repository.ReplayJobJdbcRepository;
import com.tengencorp.tengen.repository.ReplayJobOutcomeRepository;
import com.tengencorp.tengen.repository.ReplayJobRepository;
import com.tengencorp.tengen.repository.RuleRevisionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Creates immutable, analysis-only replay jobs and serves their read APIs. */
@Service
public class ReplayJobService {

    private static final int MAX_OUTCOME_PAGE_SIZE = 100;

    private final ReplayJobRepository replayJobRepository;
    private final ReplayJobOutcomeRepository outcomeRepository;
    private final RuleRevisionRepository revisionRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ReplayJobJdbcRepository jdbcRepository;
    private final ReplaySnapshotValidator snapshotValidator;
    private final ReplayProperties properties;
    private final ObjectMapper objectMapper;
    private final Counter jobsCreated;

    public ReplayJobService(ReplayJobRepository replayJobRepository,
                            ReplayJobOutcomeRepository outcomeRepository,
                            RuleRevisionRepository revisionRepository,
                            ApiKeyRepository apiKeyRepository,
                            ReplayJobJdbcRepository jdbcRepository,
                            ReplaySnapshotValidator snapshotValidator,
                            ReplayProperties properties,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this.replayJobRepository = replayJobRepository;
        this.outcomeRepository = outcomeRepository;
        this.revisionRepository = revisionRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.jdbcRepository = jdbcRepository;
        this.snapshotValidator = snapshotValidator;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jobsCreated = Counter.builder("tengen.replay.jobs.created")
            .description("Replay jobs created")
            .register(meterRegistry);
    }

    @Transactional
    public ReplayJobResponse create(ReplayJobCreateRequest request, String actor) {
        validateRequest(request);
        RuleRevision revision = revisionRepository.findForReplay(
            request.ruleId(), request.ruleRevision())
            .orElseThrow(() -> new NotFoundException(
                "Rule revision " + request.ruleRevision() + " for rule " + request.ruleId()
                    + " not found"));
        RuleSnapshot snapshot = snapshot(revision);
        snapshotValidator.validate(snapshot);

        if (request.apiKeyId() != null && !apiKeyRepository.existsById(request.apiKeyId())) {
            throw new NotFoundException("API key " + request.apiKeyId() + " not found");
        }

        long outputEvents = jdbcRepository.countEligibleOutputEvents(
            request.occurredFrom(), request.occurredTo(), request.apiKeyId(), snapshot);
        if (outputEvents == 0) {
            throw new IllegalArgumentException("No eligible events were found in the requested range");
        }
        if (outputEvents > properties.getMaxMaterializedOutputEvents()) {
            throw new IllegalArgumentException(
                "The requested range contains more than the configured replay event limit");
        }

        Instant warmupFrom = snapshot.ruleType() == RuleType.AGGREGATE
            ? request.occurredFrom().minusSeconds(snapshot.windowSeconds())
            : request.occurredFrom();
        ReplayJob job = new ReplayJob();
        job.setStatus(ReplayJobStatus.QUEUED);
        job.setRuleId(request.ruleId());
        job.setRuleRevision(request.ruleRevision());
        job.setSnapshotSchemaVersion(revision.getSnapshotSchemaVersion() != null
            ? revision.getSnapshotSchemaVersion() : 1);
        job.setRuleSnapshot(objectMapper.convertValue(snapshot, Map.class));
        job.setOccurredFrom(request.occurredFrom());
        job.setOccurredTo(request.occurredTo());
        job.setApiKeyId(request.apiKeyId());
        job.setWarmupFrom(warmupFrom);
        job.setActionMode(ReplayActionMode.NO_ACTIONS);
        job.setTotalOutputEvents(outputEvents);
        job.setCreatedBy(safeActor(actor));
        job = replayJobRepository.saveAndFlush(job);

        int materializedEvents = jdbcRepository.materialize(
            new ReplayJobJdbcRepository.ReplayMaterialization(
                job.getId(), warmupFrom, request.occurredFrom(), request.occurredTo(),
                request.apiKeyId(), snapshot));
        if (materializedEvents < outputEvents) {
            throw new IllegalStateException("Replay inputs changed while the job was being created");
        }
        job.setTotalMaterializedEvents(materializedEvents);
        job.setUpdatedAt(Instant.now());
        replayJobRepository.saveAndFlush(job);
        jobsCreated.increment();
        return ReplayJobResponse.from(job, objectMapper);
    }

    @Transactional(readOnly = true)
    public ReplayJobResponse get(Long id) {
        return ReplayJobResponse.from(findJob(id), objectMapper);
    }

    @Transactional(readOnly = true)
    public ReplayJobOutcomePage outcomes(Long id, int page, int size, Boolean matched) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be non-negative");
        }
        if (size < 1 || size > MAX_OUTCOME_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_OUTCOME_PAGE_SIZE);
        }
        findJob(id);
        Page<com.tengencorp.tengen.entity.ReplayJobOutcome> result = outcomeRepository.findPage(
            id, matched, PageRequest.of(page, size, Sort.by(Sort.Order.asc("inputPosition"))));
        return new ReplayJobOutcomePage(
            result.getContent().stream()
                .map(outcome -> ReplayJobOutcomeResponse.from(outcome, objectMapper))
                .toList(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages());
    }

    private ReplayJob findJob(Long id) {
        return replayJobRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Replay job " + id + " not found"));
    }

    private RuleSnapshot snapshot(RuleRevision revision) {
        if (revision.getSnapshot() == null) {
            throw new IllegalArgumentException("Rule revision snapshot is missing");
        }
        try {
            return objectMapper.convertValue(revision.getSnapshot(), RuleSnapshot.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Rule revision snapshot is not readable");
        }
    }

    private void validateRequest(ReplayJobCreateRequest request) {
        if (request == null || request.ruleId() == null || request.ruleRevision() == null
                || request.occurredFrom() == null || request.occurredTo() == null) {
            throw new IllegalArgumentException(
                "ruleId, ruleRevision, occurredFrom, and occurredTo are required");
        }
        if (request.ruleRevision() < 1) {
            throw new IllegalArgumentException("ruleRevision must be positive");
        }
        if (!request.occurredFrom().isBefore(request.occurredTo())) {
            throw new IllegalArgumentException("occurredFrom must be before occurredTo");
        }
        if (properties.getMaxRangeDays() < 1
                || Duration.between(request.occurredFrom(), request.occurredTo())
                    .compareTo(Duration.ofDays(properties.getMaxRangeDays())) > 0) {
            throw new IllegalArgumentException(
                "The requested range exceeds the configured maximum duration");
        }
        if (properties.getMaxMaterializedOutputEvents() < 1) {
            throw new IllegalArgumentException("Replay event limit must be positive");
        }
    }

    private String safeActor(String actor) {
        if (actor == null || actor.isBlank()) {
            return "system";
        }
        return actor.length() <= 100 ? actor : actor.substring(0, 100);
    }
}
