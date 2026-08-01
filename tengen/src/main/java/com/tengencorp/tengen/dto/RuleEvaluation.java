package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleType;

/**
 * Outcome of evaluating one rule against one event.
 *
 * @param conditionMatched whether pre-filter + Aviator condition passed
 * @param aggregateValue   windowed aggregate value, or null for CONDITION rules
 */
public record RuleEvaluation(boolean conditionMatched, Double aggregateValue) {

    public boolean matched(Rule rule) {
        if (!conditionMatched) {
            return false;
        }
        if (rule.getRuleType() == RuleType.CONDITION) {
            return true;
        }
        return aggregateValue != null && aggregateValue >= rule.getThreshold();
    }
}
