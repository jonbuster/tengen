package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.TriggerMode;
import com.tengencorp.tengen.helper.AggregateFieldPath;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for create/update rule operations. Mirrors the previous
 * {@code RuleForm} but takes {@code windowSeconds} directly (no minutes
 * conversion) and carries bean validation.
 */
public record RuleRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @NotNull(message = "Rule type is required")
        RuleType ruleType,

        @NotNull(message = "Action is required")
        RuleAction action,

        @Size(max = 500, message = "Callback URL must be at most 500 characters")
        String callbackUrl,

        @PositiveOrZero(message = "Cooldown must be zero or greater")
        Integer cooldownSeconds,

        TriggerMode triggerMode,

        @NotBlank(message = "Event type is required")
        @Size(max = 100, message = "Event type must be at most 100 characters")
        String eventType,

        @NotBlank(message = "Source is required")
        @Size(max = 100, message = "Source must be at most 100 characters")
        String source,

        @NotBlank(message = "Condition is required")
        String conditionScript,

        Integer windowSeconds,

        AggregateType aggType,

        @Size(max = 200, message = "Aggregate field must be at most 200 characters")
        String aggField,

        @Size(max = 200, message = "Group-by field must be at most 200 characters")
        String groupBy,

        Double threshold,

        boolean active) {

    public Rule toEntity() {
        Rule rule = new Rule();
        applyTo(rule);
        return rule;
    }

    public void applyTo(Rule rule) {
        rule.setName(name);
        rule.setRuleType(ruleType);
        rule.setAction(action);
        rule.setCallbackUrl(action == RuleAction.WEBHOOK ? callbackUrl : null);
        rule.setCooldownSeconds(action == RuleAction.WEBHOOK ? cooldownSeconds : null);
        rule.setTriggerMode(action == RuleAction.WEBHOOK && triggerMode != null
            ? triggerMode : TriggerMode.EVERY_MATCH);
        rule.setEventType(eventType);
        rule.setSource(source);
        rule.setConditionScript(conditionScript);
        rule.setWindowSeconds(windowSeconds);
        rule.setAggType(ruleType == RuleType.AGGREGATE ? aggType : null);
        rule.setAggField(ruleType == RuleType.AGGREGATE ? AggregateFieldPath.normalize(aggField) : null);
        rule.setGroupBy(ruleType == RuleType.AGGREGATE ? AggregateFieldPath.normalize(groupBy) : null);
        rule.setThreshold(threshold != null ? threshold : 0.0);
        rule.setActive(active);
    }
}
