package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.TriggerMode;

import java.time.Instant;

/** Stable, configuration-only representation stored in a rule revision. */
public record RuleSnapshot(
        String name,
        RuleType ruleType,
        RuleAction action,
        String callbackUrl,
        Integer cooldownSeconds,
        TriggerMode triggerMode,
        String eventType,
        String source,
        String conditionScript,
        Integer windowSeconds,
        AggregateType aggType,
        String aggField,
        String groupBy,
        Double threshold,
        boolean active,
        Instant archivedAt) {

    public static RuleSnapshot from(Rule rule) {
        return new RuleSnapshot(
            rule.getName(),
            rule.getRuleType(),
            rule.getAction(),
            rule.getCallbackUrl(),
            rule.getCooldownSeconds(),
            rule.getEffectiveTriggerMode(),
            rule.getEventType(),
            rule.getSource(),
            rule.getConditionScript(),
            rule.getWindowSeconds(),
            rule.getAggType(),
            rule.getAggField(),
            rule.getGroupBy(),
            rule.getThreshold(),
            rule.isActive(),
            rule.getArchivedAt());
    }
}
