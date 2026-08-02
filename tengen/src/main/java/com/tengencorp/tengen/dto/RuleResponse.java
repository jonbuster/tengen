package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.TriggerMode;

import java.time.Instant;

/**
 * Serialized representation of a {@link Rule} returned by the admin API.
 */
public record RuleResponse(
        Long id,
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
        Instant createdAt,
        Instant updatedAt) {

    public static RuleResponse from(Rule rule) {
        return new RuleResponse(
            rule.getId(),
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
            rule.getCreatedAt(),
            rule.getUpdatedAt());
    }
}
