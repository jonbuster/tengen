package com.tengencorp.tengen.service;

import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.TriggerMode;
import com.tengencorp.tengen.entity.RuleValidationStatus;
import com.tengencorp.tengen.entity.RuleSequenceStep;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Central validation for every path that can make a rule executable. */
@Service
public class RuleValidationService {

    private final AviatorEvaluatorInstance aviator;
    private final WebhookDestinationValidator destinationValidator;
    private final int maxExpressionLength;

    public RuleValidationService(AviatorEvaluatorInstance aviator,
                                 WebhookDestinationValidator destinationValidator,
                                 @Value("${tengen.rules.max-expression-length:10000}")
                                 int maxExpressionLength) {
        this.aviator = aviator;
        this.destinationValidator = destinationValidator;
        this.maxExpressionLength = maxExpressionLength;
    }

    public void validateAndMark(Rule rule) {
        try {
            validate(rule);
            rule.setValidationStatus(RuleValidationStatus.VALID);
            rule.setValidationError(null);
        } catch (IllegalArgumentException exception) {
            rule.setValidationStatus(RuleValidationStatus.INVALID);
            rule.setValidationError(truncate(exception.getMessage()));
            throw exception;
        }
    }

    public String validationError(Rule rule) {
        try {
            validate(rule);
            return null;
        } catch (IllegalArgumentException exception) {
            return truncate(exception.getMessage());
        }
    }

    public void validate(Rule rule) {
        require(rule.getName() != null && !rule.getName().isBlank(), "Name is required");
        require(rule.getRuleType() != null, "Rule type is required");
        require(rule.getAction() != null, "Action is required");
        if (rule.getRuleType() == RuleType.SEQUENCE) {
            validateSequence(rule);
        } else {
            require(rule.getEventType() != null && !rule.getEventType().isBlank(), "Event type is required");
            require(rule.getSource() != null && !rule.getSource().isBlank(), "Source is required");
            validateExpression(rule.getConditionScript(), "Condition");
        }

        Double threshold = rule.getThreshold();
        require(threshold != null && Double.isFinite(threshold), "Threshold must be a finite number");

        if (rule.getRuleType() == RuleType.AGGREGATE) {
            require(rule.getAggType() != null, "Aggregate type is required");
            require(rule.getWindowSeconds() != null && rule.getWindowSeconds() > 0,
                "Aggregate windowSeconds must be positive");
            if (rule.getAggType() != AggregateType.COUNT) {
                require(rule.getAggField() != null && !rule.getAggField().isBlank(),
                    rule.getAggType() + " requires an aggregate field");
            }
        }

        if (rule.getAction() == RuleAction.WEBHOOK) {
            destinationValidator.validateSyntax(rule.getCallbackUrl());
            require(rule.getCooldownSeconds() == null || rule.getCooldownSeconds() >= 0,
                "Cooldown must be zero or greater");
            if (rule.getEffectiveTriggerMode() == TriggerMode.ONCE_PER_WINDOW) {
                require(rule.getRuleType() == RuleType.AGGREGATE,
                    "ONCE_PER_WINDOW requires an aggregate rule");
            }
        }
    }

    private void validateSequence(Rule rule) {
        require(rule.getWindowSeconds() != null && rule.getWindowSeconds() > 0,
            "Sequence windowSeconds must be positive");
        require(rule.getSequenceSteps() != null
                && rule.getSequenceSteps().size() >= 2
                && rule.getSequenceSteps().size() <= 5,
            "Sequence must contain between 2 and 5 steps");

        int expectedPosition = 1;
        for (RuleSequenceStep step : rule.getSequenceSteps()) {
            require(step != null && step.getPosition() != null
                    && step.getPosition() == expectedPosition,
                "Sequence steps must be numbered consecutively from 1");
            require(step.getEventType() != null && !step.getEventType().isBlank(),
                "Sequence step event type is required");
            require(step.getSource() != null && !step.getSource().isBlank(),
                "Sequence step source is required");
            validateExpression(step.getConditionScript(), "Sequence step condition");
            expectedPosition++;
        }
    }

    private void validateExpression(String expression, String label) {
        require(expression != null && !expression.isBlank(), label + " is required");
        require(expression.length() <= maxExpressionLength,
            label + " must be at most " + maxExpressionLength + " characters");
        try {
            aviator.validate(expression);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + " is not valid Aviator: " + safeMessage(exception));
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private String truncate(String value) {
        if (value == null) {
            return "Rule validation failed";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
