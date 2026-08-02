package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleType;

/**
 * One row of the "all active rules" test result table.
 */
public record RuleResult(
        Long ruleId,
        String name,
        RuleType ruleType,
        RuleAction action,
        boolean matched,
        boolean conditionMatched,
        Double aggregateValue,
        Double threshold,
        Integer windowSeconds,
        String groupKey) {
}
