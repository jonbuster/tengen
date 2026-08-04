package com.tengencorp.tengen.service;

import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.tengencorp.tengen.dto.RuleEvaluation;
import com.tengencorp.tengen.dto.SequenceResult;
import com.tengencorp.tengen.dto.SequenceStepMatch;
import com.tengencorp.tengen.dto.SequenceStepTestResult;
import com.tengencorp.tengen.dto.SequenceTestResult;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleSequenceInstance;
import com.tengencorp.tengen.entity.RuleSequenceInstanceEvent;
import com.tengencorp.tengen.entity.RuleSequenceInstanceStatus;
import com.tengencorp.tengen.entity.RuleSequenceStep;
import com.tengencorp.tengen.repository.RuleSequenceInstanceEventRepository;
import com.tengencorp.tengen.repository.RuleSequenceInstanceRepository;
import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.helper.WarningLogRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates ordered sequence rules and manages their durable progress. */
@Service
public class SequenceRuleService {

    private static final Logger log = LoggerFactory.getLogger(SequenceRuleService.class);
    private static final int MAX_GROUP_KEY_LENGTH = 500;
    private static final String GLOBAL_SCOPE = "";

    private final AviatorEvaluatorInstance aviator;
    private final RuleSequenceInstanceRepository instanceRepository;
    private final RuleSequenceInstanceEventRepository instanceEventRepository;
    private final WarningLogRateLimiter warningLogRateLimiter = new WarningLogRateLimiter();

    public SequenceRuleService(AviatorEvaluatorInstance aviator,
                               RuleSequenceInstanceRepository instanceRepository,
                               RuleSequenceInstanceEventRepository instanceEventRepository) {
        this.aviator = aviator;
        this.instanceRepository = instanceRepository;
        this.instanceEventRepository = instanceEventRepository;
    }

    /** Evaluate one production or read-only candidate event. */
    public RuleEvaluation evaluate(Event event, Rule rule, boolean persist) {
        if (rule.getSequenceSteps() == null || rule.getSequenceSteps().isEmpty()
                || rule.getWindowSeconds() == null || rule.getWindowSeconds() <= 0) {
            return new RuleEvaluation(false, null, null, null, null);
        }

        List<Integer> matchingPositions = matchingPositions(event, rule);
        String groupKey = extractGroupKey(event, rule);
        if (matchingPositions.isEmpty()) {
            return new RuleEvaluation(false, null, groupKey, null, null);
        }
        if (usesGrouping(rule) && (groupKey == null || groupKey.length() > MAX_GROUP_KEY_LENGTH)) {
            return new RuleEvaluation(true, null, null, null, matchingPositions.get(0));
        }

        String scopeKey = groupKey != null ? groupKey : GLOBAL_SCOPE;
        Instant occurredAt = event.getOccurredAt();
        Long eventId = effectiveEventId(event);
        Instant windowStart = occurredAt.minusSeconds(rule.getWindowSeconds());

        List<RuleSequenceInstance> eligible = persist
            ? instanceRepository.findOldestEligibleForUpdate(
                rule.getId(), rule.getEffectiveRevision(), scopeKey,
                RuleSequenceInstanceStatus.ACTIVE, matchingPositions,
                windowStart, occurredAt, eventId, PageRequest.of(0, 1))
            : instanceRepository.findOldestEligible(
                rule.getId(), rule.getEffectiveRevision(), scopeKey,
                RuleSequenceInstanceStatus.ACTIVE, matchingPositions,
                windowStart, occurredAt, eventId, PageRequest.of(0, 1));

        if (!eligible.isEmpty()) {
            RuleSequenceInstance instance = eligible.get(0);
            int matchedPosition = instance.getNextStepPosition();
            boolean completed = matchedPosition == rule.getSequenceSteps().size();
            if (persist) {
                instance.setLastOccurredAt(occurredAt);
                instance.setLastEventId(eventId);
                instance.setNextStepPosition(completed ? null : matchedPosition + 1);
                instance.setStatus(completed
                    ? RuleSequenceInstanceStatus.COMPLETED
                    : RuleSequenceInstanceStatus.ACTIVE);
                if (completed) {
                    instance.setCompletedAt(Instant.now());
                }
                instanceRepository.save(instance);
                instanceEventRepository.saveAndFlush(new RuleSequenceInstanceEvent(
                    instance, event, matchedPosition, occurredAt));
            }
            SequenceResult result = completed
                ? sequenceResult(instance, event, matchedPosition, rule, groupKey)
                : null;
            return new RuleEvaluation(true, null, groupKey, result, matchedPosition);
        }

        if (matchingPositions.contains(1)) {
            if (persist) {
                RuleSequenceInstance instance = instanceRepository.save(
                    new RuleSequenceInstance(rule, scopeKey, 2, occurredAt, eventId));
                instanceEventRepository.save(new RuleSequenceInstanceEvent(instance, event, 1, occurredAt));
            }
            return new RuleEvaluation(true, null, groupKey, null, 1);
        }

        return new RuleEvaluation(true, null, groupKey, null, matchingPositions.get(0));
    }

    /** Simulate one event per configured step without reading or changing runtime state. */
    public SequenceTestResult testSequence(List<Event> events, Rule rule) {
        List<RuleSequenceStep> configuredSteps = rule.getSequenceSteps() != null
            ? rule.getSequenceSteps() : List.of();
        if (configuredSteps.size() < 2
                || events == null || events.size() != configuredSteps.size()) {
            return new SequenceTestResult(false, false, false, false, null, List.of(), null);
        }

        List<SequenceStepTestResult> stepResults = new ArrayList<>();
        List<SequenceStepMatch> matches = new ArrayList<>();
        boolean allConditions = true;
        boolean correlationMatched = true;
        boolean orderingValid = true;
        String groupKey = null;
        Set<String> groupKeys = new HashSet<>();

        for (int index = 0; index < configuredSteps.size(); index++) {
            Event event = events.get(index);
            RuleSequenceStep step = configuredSteps.get(index);
            boolean conditionMatched = matchesStep(event, rule, step);
            allConditions = allConditions && conditionMatched;
            stepResults.add(new SequenceStepTestResult(index + 1, conditionMatched,
                event.getOccurredAt()));
            matches.add(new SequenceStepMatch(index + 1, event.getId(), event.getOccurredAt()));

            String currentGroup = extractGroupKey(event, rule);
            if (usesGrouping(rule)) {
                if (currentGroup == null || currentGroup.length() > MAX_GROUP_KEY_LENGTH) {
                    correlationMatched = false;
                } else {
                    groupKeys.add(currentGroup);
                    if (groupKey == null) {
                        groupKey = currentGroup;
                    }
                }
            }

            if (index > 0 && compareEventOrder(events.get(index - 1), event) >= 0) {
                orderingValid = false;
            }
        }

        if (usesGrouping(rule) && groupKeys.size() > 1) {
            correlationMatched = false;
        }
        boolean withinWindow = events.get(events.size() - 1).getOccurredAt()
            .isBefore(events.get(0).getOccurredAt().plusSeconds(rule.getWindowSeconds()));
        boolean matched = allConditions && correlationMatched && orderingValid && withinWindow;
        SequenceResult result = matched
            ? new SequenceResult(groupKey, rule.getWindowSeconds(), matches) : null;
        return new SequenceTestResult(
            matched,
            !usesGrouping(rule) || correlationMatched,
            orderingValid,
            withinWindow,
            groupKey,
            stepResults,
            result);
    }

    private SequenceResult sequenceResult(RuleSequenceInstance instance, Event currentEvent,
                                          int currentPosition, Rule rule, String groupKey) {
        List<SequenceStepMatch> matches = new ArrayList<>();
        for (RuleSequenceInstanceEvent matchedEvent
                : instanceEventRepository.findByInstanceIdOrderByStepPositionAsc(instance.getId())) {
            matches.add(new SequenceStepMatch(
                matchedEvent.getStepPosition(),
                matchedEvent.getEvent().getId(),
                matchedEvent.getOccurredAt()));
        }
        if (matches.stream().noneMatch(match -> match.position() == currentPosition)) {
            matches.add(new SequenceStepMatch(currentPosition, currentEvent.getId(),
                currentEvent.getOccurredAt()));
        }
        matches.sort(java.util.Comparator.comparingInt(SequenceStepMatch::position));
        return new SequenceResult(groupKey, rule.getWindowSeconds(), matches);
    }

    private List<Integer> matchingPositions(Event event, Rule rule) {
        List<Integer> positions = new ArrayList<>();
        for (RuleSequenceStep step : rule.getSequenceSteps()) {
            if (matchesStep(event, rule, step)) {
                positions.add(step.getPosition());
            }
        }
        return positions;
    }

    private boolean matchesStep(Event event, Rule rule, RuleSequenceStep step) {
        if (!step.getEventType().equals(event.getType()) || !step.getSource().equals(event.getSource())) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(aviator.execute(step.getConditionScript(), buildEnv(event)));
        } catch (Exception exception) {
            String key = String.valueOf(rule.getId()) + ':' + rule.getEffectiveRevision()
                + ':' + step.getPosition();
            if (warningLogRateLimiter.tryAcquire("sequence_step_condition_failed", key)) {
                log.warn(
                    "event=rule_evaluation_failure name=sequence_step_condition_failed ruleId={} revision={} eventId={} stepPosition={} exceptionType={}",
                    rule.getId(), rule.getEffectiveRevision(), event.getId(), step.getPosition(),
                    LogSafe.exceptionType(exception));
            }
            return false;
        }
    }

    private Map<String, Object> buildEnv(Event event) {
        Map<String, Object> env = new HashMap<>();
        env.put("type", event.getType());
        env.put("source", event.getSource());
        env.put("timestamp", event.getOccurredAt());
        env.put("data", event.getData());
        return env;
    }

    private String extractGroupKey(Event event, Rule rule) {
        if (!usesGrouping(rule)) {
            return null;
        }
        Object value = RuleEngine.resolvePath(event.getData(), rule.getGroupBy());
        if (value == null || value instanceof Map<?, ?>) {
            return null;
        }
        String groupKey = String.valueOf(value).trim();
        return groupKey.isBlank() ? null : groupKey;
    }

    private boolean usesGrouping(Rule rule) {
        return rule.getGroupBy() != null && !rule.getGroupBy().isBlank();
    }

    private Long effectiveEventId(Event event) {
        return event.getId() != null ? event.getId() : Long.MAX_VALUE;
    }

    private int compareEventOrder(Event previous, Event current) {
        int timeComparison = previous.getOccurredAt().compareTo(current.getOccurredAt());
        if (timeComparison != 0) {
            return timeComparison;
        }
        if (previous.getId() != null && current.getId() != null) {
            return previous.getId().compareTo(current.getId());
        }
        return -1;
    }
}
