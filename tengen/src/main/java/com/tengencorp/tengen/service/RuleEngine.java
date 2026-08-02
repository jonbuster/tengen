package com.tengencorp.tengen.service;
import com.tengencorp.tengen.dto.RuleEvaluation;

import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleEvent;
import com.tengencorp.tengen.repository.RuleEventRepository;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.helper.AggregateFieldPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Evaluates a single event against a single rule.
 *
 * <p>The event map passed to Aviator mirrors the original request shape:
 * {@code {type, source, timestamp, data}} so scripts like
 * {@code data.amount >= 1000 && data.country == 'PH'} work as-is.
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final AviatorEvaluatorInstance aviator;
    private final RuleEventRepository ruleEventRepository;

    public RuleEngine(AviatorEvaluatorInstance aviator, RuleEventRepository ruleEventRepository) {
        this.aviator = aviator;
        this.ruleEventRepository = ruleEventRepository;
    }

    /**
     * Evaluate one rule against one event. Persists a rule_event row when the
     * pre-filter and condition pass (for both rule types, per plan).
     *
     * @param event persisted event
     * @param rule   active rule
     * @return evaluation outcome; conditionMatched true when pre-filter + condition pass
     */
    public RuleEvaluation evaluate(Event event, Rule rule) {
        return evaluateInternal(event, rule, true);
    }

    /**
     * Evaluate without persisting a rule_event row — used by the admin "test rule" box.
     * The window aggregate reflects already-persisted rows plus the candidate
     * event, without saving the candidate.
     */
    public RuleEvaluation test(Event event, Rule rule) {
        return evaluateInternal(event, rule, false);
    }

    private RuleEvaluation evaluateInternal(Event event, Rule rule, boolean persist) {
        Map<String, Object> env = buildEnv(event);

        String groupKey = rule.getRuleType() == RuleType.AGGREGATE
            ? extractGroupKey(event, rule) : null;

        boolean condition = passesPreFilter(event, rule) && evalCondition(rule, env);
        if (!condition) {
            return new RuleEvaluation(false, null, groupKey);
        }

        Instant occurredAt = event.getOccurredAt();

        if (rule.getRuleType() == RuleType.AGGREGATE) {
            Double value = extractNumericValue(event, rule.getAggField());
            if (usesGrouping(rule) && groupKey == null) {
                return new RuleEvaluation(true, null, null);
            }
            if (persist) {
                ruleEventRepository.save(new RuleEvent(rule, event, value, groupKey, occurredAt));
            }
            double window = aggregate(rule, occurredAt, !persist, value, groupKey);
            return new RuleEvaluation(true, window, groupKey);
        }

        // CONDITION rule — record the row for future upgrades, no lookback.
        if (persist) {
            ruleEventRepository.save(new RuleEvent(rule, event, null, occurredAt));
        }
        return new RuleEvaluation(true, null);
    }

    private boolean passesPreFilter(Event event, Rule rule) {
        return rule.getEventType().equals(event.getType())
            && rule.getSource().equals(event.getSource());
    }

    private boolean evalCondition(Rule rule, Map<String, Object> env) {
        try {
            Object result = aviator.execute(rule.getConditionScript(), env);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            // Script errors never fail the request — treat as non-match.
            log.warn("Rule [{}] condition script evaluation failed: {}", rule.getName(), e.getMessage());
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

    private Double extractNumericValue(Event event, String aggField) {
        if (aggField == null || aggField.isBlank()) {
            return null;
        }
        Object value = resolvePath(event.getData(), aggField);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private String extractGroupKey(Event event, Rule rule) {
        if (!usesGrouping(rule)) {
            return null;
        }
        Object value = resolvePath(event.getData(), rule.getGroupBy());
        if (value == null || value instanceof Map<?, ?>) {
            return null;
        }
        String groupKey = String.valueOf(value).trim();
        return groupKey.isBlank() ? null : groupKey;
    }

    private boolean usesGrouping(Rule rule) {
        return rule.getGroupBy() != null && !rule.getGroupBy().isBlank();
    }

    /**
     * Resolve a dotted path like {@code data.amount} or {@code amount} against the event map.
     */
    static Object resolvePath(Map<String, Object> map, String path) {
        Object current = map;
        String normalizedPath = AggregateFieldPath.normalize(path);
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        for (String part : normalizedPath.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private double aggregate(Rule rule, Instant occurredAt, boolean includeCandidate,
                             Double candidateValue, String groupKey) {
        Instant since = occurredAt.minusSeconds(rule.getWindowSeconds());
        Instant until = occurredAt;
        AggregateType type = rule.getAggType();
        Long ruleId = rule.getId();
        return switch (type) {
            case COUNT -> ruleEventRepository.countInWindow(ruleId, since, until, groupKey)
                + (includeCandidate ? 1 : 0);
            case SUM -> ruleEventRepository.sumInWindow(ruleId, since, until, groupKey)
                + (includeCandidate ? numericCandidateValue(candidateValue) : 0.0);
            case AVG -> average(ruleId, since, until, includeCandidate, candidateValue, groupKey);
            case MIN -> minimum(ruleId, since, until, includeCandidate, candidateValue, groupKey);
            case MAX -> maximum(ruleId, since, until, includeCandidate, candidateValue, groupKey);
        };
    }

    private double average(Long ruleId, Instant since, Instant until,
                           boolean includeCandidate, Double candidateValue, String groupKey) {
        double sum = ruleEventRepository.sumInWindow(ruleId, since, until, groupKey);
        long count = ruleEventRepository.countValuesInWindow(ruleId, since, until, groupKey);
        if (includeCandidate && candidateValue != null) {
            sum += candidateValue;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double minimum(Long ruleId, Instant since, Instant until,
                           boolean includeCandidate, Double candidateValue, String groupKey) {
        Double minimum = ruleEventRepository.minInWindow(ruleId, since, until, groupKey);
        if (includeCandidate && candidateValue != null) {
            minimum = minimum == null ? candidateValue : Math.min(minimum, candidateValue);
        }
        return minimum != null ? minimum : 0.0;
    }

    private double maximum(Long ruleId, Instant since, Instant until,
                           boolean includeCandidate, Double candidateValue, String groupKey) {
        Double maximum = ruleEventRepository.maxInWindow(ruleId, since, until, groupKey);
        if (includeCandidate && candidateValue != null) {
            maximum = maximum == null ? candidateValue : Math.max(maximum, candidateValue);
        }
        return maximum != null ? maximum : 0.0;
    }

    private double numericCandidateValue(Double candidateValue) {
        return candidateValue != null ? candidateValue : 0.0;
    }
}
