package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.RuleRequest;
import com.tengencorp.tengen.dto.RuleResponse;
import com.tengencorp.tengen.dto.RuleRevisionDetail;
import com.tengencorp.tengen.dto.RuleRevisionPage;
import com.tengencorp.tengen.dto.RuleRevisionSummary;
import com.tengencorp.tengen.dto.RuleSnapshot;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleRevision;
import com.tengencorp.tengen.entity.RuleRevisionChangeType;
import com.tengencorp.tengen.exception.ConflictException;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.repository.RuleActionStateRepository;
import com.tengencorp.tengen.repository.RuleActionWindowRepository;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.repository.RuleRevisionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Owns rule mutations and keeps the current projection and audit history atomic. */
@Service
public class RuleLifecycleService {

    private static final int MAX_PAGE_SIZE = 100;

    private final RuleRepository ruleRepository;
    private final RuleRevisionRepository revisionRepository;
    private final RuleActionStateRepository actionStateRepository;
    private final RuleActionWindowRepository actionWindowRepository;
    private final ObjectMapper objectMapper;
    private final RuleValidationService validationService;

    public RuleLifecycleService(RuleRepository ruleRepository,
                                RuleRevisionRepository revisionRepository,
                                RuleActionStateRepository actionStateRepository,
                                RuleActionWindowRepository actionWindowRepository,
                                ObjectMapper objectMapper,
                                RuleValidationService validationService) {
        this.ruleRepository = ruleRepository;
        this.revisionRepository = revisionRepository;
        this.actionStateRepository = actionStateRepository;
        this.actionWindowRepository = actionWindowRepository;
        this.objectMapper = objectMapper;
        this.validationService = validationService;
    }

    @Transactional
    public RuleResponse create(RuleRequest request, String actor) {
        if (ruleRepository.existsByName(request.name())) {
            throw new ConflictException("A rule named '" + request.name() + "' already exists");
        }
        Rule rule = request.toEntity();
        validationService.validateAndMark(rule);
        rule.setRevision(1);
        rule.setArchivedAt(null);
        rule = ruleRepository.save(rule);
        record(rule, RuleRevisionChangeType.CREATED, actor, null);
        return RuleResponse.from(rule);
    }

    @Transactional
    public RuleResponse update(Long id, RuleRequest request, String ifMatch, String actor) {
        Rule rule = locked(id);
        assertExpectedRevision(rule, ifMatch);
        if (ruleRepository.findByName(request.name())
            .filter(existing -> !Objects.equals(existing.getId(), id))
            .isPresent()) {
            throw new ConflictException("A rule named '" + request.name() + "' already exists");
        }

        Rule proposed = request.toEntity();
        validationService.validateAndMark(proposed);
        proposed.setArchivedAt(rule.getArchivedAt());
        if (rule.isArchived()) {
            proposed.setActive(false);
        }
        if (sameSnapshot(rule, proposed)) {
            return RuleResponse.from(rule);
        }

        request.applyTo(rule);
        validationService.validateAndMark(rule);
        rule.setArchivedAt(proposed.getArchivedAt());
        if (rule.isArchived()) {
            rule.setActive(false);
        }
        incrementRevision(rule);
        resetRuntimeState(rule);
        rule = ruleRepository.save(rule);
        record(rule, RuleRevisionChangeType.UPDATED, actor, null);
        return RuleResponse.from(rule);
    }

    @Transactional
    public RuleResponse toggle(Long id, String ifMatch, String actor) {
        Rule rule = locked(id);
        assertExpectedRevision(rule, ifMatch);
        if (rule.isArchived()) {
            throw new ConflictException("Archived rules must be unarchived before activation changes");
        }
        boolean activating = !rule.isActive();
        if (activating) {
            validationService.validateAndMark(rule);
        }
        rule.setActive(activating);
        incrementRevision(rule);
        resetRuntimeState(rule);
        rule = ruleRepository.save(rule);
        record(rule,
            rule.isActive() ? RuleRevisionChangeType.ACTIVATED : RuleRevisionChangeType.DEACTIVATED,
            actor, null);
        return RuleResponse.from(rule);
    }

    @Transactional
    public void archive(Long id, String ifMatch, String actor) {
        Rule rule = locked(id);
        assertExpectedRevision(rule, ifMatch);
        if (rule.isArchived()) {
            return;
        }
        rule.setActive(false);
        rule.setArchivedAt(Instant.now());
        incrementRevision(rule);
        resetRuntimeState(rule);
        ruleRepository.save(rule);
        record(rule, RuleRevisionChangeType.ARCHIVED, actor, null);
    }

    @Transactional
    public RuleResponse unarchive(Long id, String ifMatch, String actor) {
        Rule rule = locked(id);
        assertExpectedRevision(rule, ifMatch);
        if (!rule.isArchived()) {
            return RuleResponse.from(rule);
        }
        rule.setArchivedAt(null);
        rule.setActive(false);
        incrementRevision(rule);
        resetRuntimeState(rule);
        rule = ruleRepository.save(rule);
        record(rule, RuleRevisionChangeType.UNARCHIVED, actor, null);
        return RuleResponse.from(rule);
    }

    @Transactional
    public RuleResponse restore(Long id, int sourceRevision, String ifMatch, String actor) {
        Rule rule = locked(id);
        assertExpectedRevision(rule, ifMatch);
        RuleRevision source = revisionRepository.findByRuleIdAndRevision(id, sourceRevision)
            .orElseThrow(() -> new NotFoundException(
                "Revision " + sourceRevision + " for rule " + id + " not found"));
        RuleSnapshot snapshot = snapshot(source);
        if (ruleRepository.findByName(snapshot.name())
            .filter(existing -> !Objects.equals(existing.getId(), id))
            .isPresent()) {
            throw new ConflictException("Cannot restore because the snapshot name is already in use");
        }

        applySnapshot(rule, snapshot);
        validationService.validateAndMark(rule);
        rule.setActive(false);
        rule.setArchivedAt(null);
        incrementRevision(rule);
        resetRuntimeState(rule);
        rule = ruleRepository.save(rule);
        record(rule, RuleRevisionChangeType.RESTORED, actor, sourceRevision);
        return RuleResponse.from(rule);
    }

    @Transactional(readOnly = true)
    public RuleRevisionPage revisions(Long ruleId, int page, int size) {
        ensureExists(ruleId);
        validatePage(page, size);
        Page<RuleRevision> result = revisionRepository.findByRuleId(
            ruleId,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("revision"))));
        return new RuleRevisionPage(
            result.map(RuleRevisionSummary::from).getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public RuleRevisionDetail revision(Long ruleId, int revision) {
        ensureExists(ruleId);
        RuleRevision result = revisionRepository.findByRuleIdAndRevision(ruleId, revision)
            .orElseThrow(() -> new NotFoundException(
                "Revision " + revision + " for rule " + ruleId + " not found"));
        return new RuleRevisionDetail(
            RuleRevisionSummary.from(result),
            result.getSnapshotSchemaVersion() != null ? result.getSnapshotSchemaVersion() : 1,
            snapshot(result));
    }

    @Transactional(readOnly = true)
    public Rule find(Long id) {
        return ruleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Rule " + id + " not found"));
    }

    private Rule locked(Long id) {
        return ruleRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Rule " + id + " not found"));
    }

    private void ensureExists(Long id) {
        if (!ruleRepository.existsById(id)) {
            throw new NotFoundException("Rule " + id + " not found");
        }
    }

    private void incrementRevision(Rule rule) {
        rule.setRevision(rule.getEffectiveRevision() + 1);
    }

    private void resetRuntimeState(Rule rule) {
        actionStateRepository.deleteByRuleId(rule.getId());
        actionWindowRepository.deleteByRuleId(rule.getId());
    }

    private boolean sameSnapshot(Rule current, Rule proposed) {
        RuleSnapshot currentSnapshot = RuleSnapshot.from(current);
        RuleSnapshot proposedSnapshot = RuleSnapshot.from(proposed);
        return Objects.equals(currentSnapshot, proposedSnapshot);
    }

    private void assertExpectedRevision(Rule rule, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return;
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
            if (expected != rule.getEffectiveRevision()) {
                throw new ConflictException(
                    "Rule changed since it was loaded; refresh before saving (current revision "
                        + rule.getEffectiveRevision() + ")");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must contain a rule revision number");
        }
    }

    private void record(Rule rule, RuleRevisionChangeType changeType, String actor,
                        Integer restoredFromRevision) {
        revisionRepository.save(new RuleRevision(
            rule.getId(),
            rule.getEffectiveRevision(),
            changeType,
            actor == null || actor.isBlank() ? "system" : actor,
            snapshotMap(rule),
            restoredFromRevision));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotMap(Rule rule) {
        return objectMapper.convertValue(RuleSnapshot.from(rule), Map.class);
    }

    private RuleSnapshot snapshot(RuleRevision revision) {
        if (revision.getSnapshot() == null) {
            throw new IllegalStateException("Rule revision has no snapshot");
        }
        return objectMapper.convertValue(revision.getSnapshot(), RuleSnapshot.class);
    }

    private void applySnapshot(Rule rule, RuleSnapshot snapshot) {
        if (snapshot.name() == null || snapshot.ruleType() == null || snapshot.action() == null
            || snapshot.eventType() == null || snapshot.source() == null
            || snapshot.conditionScript() == null) {
            throw new IllegalArgumentException("Rule revision snapshot is incomplete");
        }
        rule.setName(snapshot.name());
        rule.setRuleType(snapshot.ruleType());
        rule.setAction(snapshot.action());
        rule.setCallbackUrl(snapshot.callbackUrl());
        rule.setCooldownSeconds(snapshot.cooldownSeconds());
        rule.setTriggerMode(snapshot.triggerMode());
        rule.setEventType(snapshot.eventType());
        rule.setSource(snapshot.source());
        rule.setConditionScript(snapshot.conditionScript());
        rule.setWindowSeconds(snapshot.windowSeconds());
        rule.setAggType(snapshot.aggType());
        rule.setAggField(snapshot.aggField());
        rule.setGroupBy(snapshot.groupBy());
        rule.setThreshold(snapshot.threshold() != null ? snapshot.threshold() : 0.0);
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be non-negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
