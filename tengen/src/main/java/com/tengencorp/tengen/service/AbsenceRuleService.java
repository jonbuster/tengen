package com.tengencorp.tengen.service;

import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.tengencorp.tengen.dto.AbsenceResult;
import com.tengencorp.tengen.dto.AbsenceTestResult;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAbsenceInstance;
import com.tengencorp.tengen.entity.RuleAbsenceInstanceStatus;
import com.tengencorp.tengen.repository.RuleAbsenceInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Opens and satisfies durable absence expectations during event ingestion. */
@Service
public class AbsenceRuleService {

    private static final Logger log = LoggerFactory.getLogger(AbsenceRuleService.class);
    private static final int MAX_GROUP_KEY_LENGTH = 500;
    private static final String GLOBAL_SCOPE = "";

    private final AviatorEvaluatorInstance aviator;
    private final RuleAbsenceInstanceRepository instanceRepository;

    public AbsenceRuleService(AviatorEvaluatorInstance aviator,
                              RuleAbsenceInstanceRepository instanceRepository) {
        this.aviator = aviator;
        this.instanceRepository = instanceRepository;
    }

    /** Process one production event. The caller owns the ingestion transaction. */
    public ProcessingResult process(Event event, Rule rule) {
        if (rule.getRuleType() != com.tengencorp.tengen.entity.RuleType.ABSENCE) {
            return ProcessingResult.none();
        }

        String groupKey = extractGroupKey(event, rule);
        if (usesGrouping(rule)
                && (groupKey == null || groupKey.length() > MAX_GROUP_KEY_LENGTH)) {
            return new ProcessingResult(false, false, false, false, groupKey, null);
        }
        String scopeKey = groupKey != null ? groupKey : GLOBAL_SCOPE;

        boolean expectedMatched = isExpectedEvent(event, rule) && evaluates(
            rule.getExpectedConditionScript(), event, rule.getName(), "expected");
        boolean startMatched = isStartEvent(event, rule) && evaluates(
            rule.getConditionScript(), event, rule.getName(), "starting");

        boolean satisfied = false;
        RuleAbsenceInstance satisfiedInstance = null;
        if (expectedMatched && event.getId() != null) {
            var candidates = instanceRepository.findSatisfiable(
                rule.getId(), rule.getEffectiveRevision(), scopeKey,
                RuleAbsenceInstanceStatus.PENDING, event.getOccurredAt(), event.getId(),
                PageRequest.of(0, 1));
            if (!candidates.isEmpty()) {
                satisfiedInstance = candidates.get(0);
                satisfiedInstance.setStatus(RuleAbsenceInstanceStatus.SATISFIED);
                satisfiedInstance.setResolvedByEvent(event);
                satisfiedInstance.setResolvedAt(Instant.now());
                instanceRepository.save(satisfiedInstance);
                satisfied = true;
            }
        }

        boolean opened = false;
        RuleAbsenceInstance openedInstance = null;
        if (startMatched && event.getId() != null) {
            Instant deadline = event.getOccurredAt().plusSeconds(rule.getWindowSeconds());
            int inserted = instanceRepository.insertPending(
                rule.getId(),
                rule.getEffectiveRevision(),
                scopeKey,
                event.getId(),
                event.getOccurredAt(),
                deadline,
                Instant.now());
            if (inserted == 1) {
                openedInstance = instanceRepository.findPendingForUpdate(
                    rule.getId(), rule.getEffectiveRevision(), scopeKey,
                    RuleAbsenceInstanceStatus.PENDING).orElse(null);
                opened = true;
            }
        }

        return new ProcessingResult(startMatched, expectedMatched, opened, satisfied,
            groupKey, satisfiedInstance != null ? satisfiedInstance : openedInstance);
    }

    /** Simulate the two-event absence contract without reading or changing runtime state. */
    public AbsenceTestResult test(Event start, Event expected, Rule rule) {
        String groupKey = extractGroupKey(start, rule);
        boolean validGroup = !usesGrouping(rule)
            || (groupKey != null && groupKey.length() <= MAX_GROUP_KEY_LENGTH);
        boolean startMatched = validGroup && isStartEvent(start, rule)
            && evaluates(rule.getConditionScript(), start, rule.getName(), "starting");
        if (!startMatched) {
            return new AbsenceTestResult(false, false, validGroup, false, false,
                "START_NOT_MATCHED", groupKey, null);
        }
        if (expected == null) {
            Instant deadline = start.getOccurredAt().plusSeconds(rule.getWindowSeconds());
            return new AbsenceTestResult(true, false, validGroup, true, true,
                "WOULD_TRIGGER", groupKey,
                new AbsenceResult(null, groupKey, expectedId(start), start.getOccurredAt(),
                    rule.getExpectedEventType(), rule.getExpectedSource(), deadline, deadline));
        }

        String expectedGroupKey = extractGroupKey(expected, rule);
        boolean correlationMatched = !usesGrouping(rule)
            || (expectedGroupKey != null && expectedGroupKey.equals(groupKey));
        boolean expectedMatched = isExpectedEvent(expected, rule)
            && evaluates(rule.getExpectedConditionScript(), expected, rule.getName(), "expected");
        boolean orderingValid = expected.getOccurredAt().isAfter(start.getOccurredAt())
            || (expected.getOccurredAt().equals(start.getOccurredAt())
                && expectedId(expected) > expectedId(start));
        Instant deadline = start.getOccurredAt().plusSeconds(rule.getWindowSeconds());
        boolean withinWindow = !expected.getOccurredAt().isAfter(deadline);
        String outcome = !expectedMatched ? "EXPECTED_NOT_MATCHED"
            : !correlationMatched ? "CORRELATION_MISMATCH"
            : !orderingValid ? "OUTSIDE_ORDER"
            : !withinWindow ? "OUTSIDE_WINDOW"
            : "WOULD_BE_SATISFIED";
        return new AbsenceTestResult(true, expectedMatched, correlationMatched, orderingValid,
            withinWindow, outcome, groupKey,
            new AbsenceResult(null, groupKey, expectedId(start), start.getOccurredAt(),
                rule.getExpectedEventType(), rule.getExpectedSource(), deadline,
                expected.getOccurredAt()));
    }

    private boolean isStartEvent(Event event, Rule rule) {
        return rule.getEventType() != null
            && rule.getEventType().equals(event.getType())
            && rule.getSource() != null
            && rule.getSource().equals(event.getSource());
    }

    private boolean isExpectedEvent(Event event, Rule rule) {
        return rule.getExpectedEventType() != null
            && rule.getExpectedEventType().equals(event.getType())
            && rule.getExpectedSource() != null
            && rule.getExpectedSource().equals(event.getSource());
    }

    private boolean evaluates(String script, Event event, String ruleName, String pattern) {
        try {
            Object result = aviator.execute(script, buildEnv(event));
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException exception) {
            log.warn("Absence {} condition evaluation failed for rule [{}]: {}",
                pattern, ruleName, exception.getMessage());
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

    private long expectedId(Event event) {
        return event != null && event.getId() != null ? event.getId() : 0L;
    }

    public record ProcessingResult(boolean startMatched, boolean expectedMatched,
                                   boolean opened, boolean satisfied, String groupKey,
                                   RuleAbsenceInstance instance) {
        static ProcessingResult none() {
            return new ProcessingResult(false, false, false, false, null, null);
        }
    }
}
