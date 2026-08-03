package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleType;

/**
 * Outcome of evaluating one rule against one event.
 *
 * @param conditionMatched whether pre-filter + Aviator condition passed
 * @param aggregateValue   windowed aggregate value, or null for CONDITION rules
 * @param groupKey         resolved grouping key, or null for global aggregates
 * @param sequence         completed sequence details, or null when progress is incomplete
 * @param sequenceStep     current sequence position matched by the event, when applicable
 */
public record RuleEvaluation(boolean conditionMatched, Double aggregateValue, String groupKey,
                             SequenceResult sequence, Integer sequenceStep) {

    public RuleEvaluation(boolean conditionMatched, Double aggregateValue) {
        this(conditionMatched, aggregateValue, null, null, null);
    }

    public RuleEvaluation(boolean conditionMatched, Double aggregateValue, String groupKey) {
        this(conditionMatched, aggregateValue, groupKey, null, null);
    }

    public RuleEvaluation(boolean conditionMatched, Double aggregateValue, String groupKey,
                          SequenceResult sequence) {
        this(conditionMatched, aggregateValue, groupKey, sequence, null);
    }

    public boolean matched(Rule rule) {
        if (!conditionMatched) {
            return false;
        }
        if (rule.getRuleType() == RuleType.SEQUENCE) {
            return sequence != null;
        }
        if (rule.getRuleType() == RuleType.CONDITION) {
            return true;
        }
        return aggregateValue != null && aggregateValue >= rule.getThreshold();
    }
}
