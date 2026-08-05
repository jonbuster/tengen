package com.tengencorp.tengen.service;

import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.TriggerMode;
import com.tengencorp.tengen.entity.RuleValidationStatus;
import com.tengencorp.tengen.entity.RuleSequenceStep;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationRecipientMode;
import com.tengencorp.tengen.repository.NotificationDestinationRepository;
import com.tengencorp.tengen.repository.NotificationTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/** Central validation for every path that can make a rule executable. */
@Service
public class RuleValidationService {

    private final AviatorEvaluatorInstance aviator;
    private final WebhookDestinationValidator destinationValidator;
    private final NotificationDestinationRepository notificationDestinationRepository;
    private final NotificationTemplateRepository notificationTemplateRepository;
    private final int maxExpressionLength;

    @Autowired
    public RuleValidationService(AviatorEvaluatorInstance aviator,
                                 WebhookDestinationValidator destinationValidator,
                                 NotificationDestinationRepository notificationDestinationRepository,
                                 NotificationTemplateRepository notificationTemplateRepository,
                                 @Value("${tengen.rules.max-expression-length:10000}")
                                 int maxExpressionLength) {
        this.aviator = aviator;
        this.destinationValidator = destinationValidator;
        this.notificationDestinationRepository = notificationDestinationRepository;
        this.notificationTemplateRepository = notificationTemplateRepository;
        this.maxExpressionLength = maxExpressionLength;
    }

    /** Compatibility constructor used by focused unit tests and older callers. */
    public RuleValidationService(AviatorEvaluatorInstance aviator,
                                 WebhookDestinationValidator destinationValidator,
                                 int maxExpressionLength) {
        this(aviator, destinationValidator, null, null, maxExpressionLength);
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
        } else if (rule.getRuleType() == RuleType.ABSENCE) {
            validateAbsence(rule);
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

        if (rule.getRuleType() == RuleType.ABSENCE) {
            require(rule.getWindowSeconds() != null && rule.getWindowSeconds() > 0,
                "Absence windowSeconds must be positive");
        }

        if (rule.getAction() == RuleAction.WEBHOOK) {
            destinationValidator.validateSyntax(rule.getCallbackUrl());
        } else if (rule.getAction() == RuleAction.EMAIL || rule.getAction() == RuleAction.SMS) {
            validateNotification(rule);
        }

        if (rule.getAction() == RuleAction.WEBHOOK
                || rule.getAction() == RuleAction.EMAIL
                || rule.getAction() == RuleAction.SMS) {
            require(rule.getCooldownSeconds() == null || rule.getCooldownSeconds() >= 0,
                "Cooldown must be zero or greater");
            if (rule.getEffectiveTriggerMode() == TriggerMode.ONCE_PER_WINDOW) {
                require(rule.getRuleType() == RuleType.AGGREGATE,
                    "ONCE_PER_WINDOW requires an aggregate rule");
            }
            if (rule.getRuleType() == RuleType.ABSENCE) {
                require(rule.getEffectiveTriggerMode() == TriggerMode.EVERY_MATCH,
                    "Absence notification rules must use EVERY_MATCH trigger mode");
            }
        }
    }

    private void validateNotification(Rule rule) {
        NotificationChannel channel = rule.getAction() == RuleAction.EMAIL
            ? NotificationChannel.EMAIL : NotificationChannel.SMS;
        require(rule.getNotificationDestinationId() != null,
            "Notification destination is required");
        require(rule.getNotificationTemplateId() != null,
            "Notification template is required");
        if (notificationDestinationRepository != null) {
            var destination = notificationDestinationRepository.findById(rule.getNotificationDestinationId())
                .orElseThrow(() -> new IllegalArgumentException("Notification destination was not found"));
            require(destination.isEnabled(), "Notification destination must be enabled");
            require(destination.getChannel() == channel,
                "Notification destination channel must match the action");
        }
        if (notificationTemplateRepository != null) {
            var template = notificationTemplateRepository.findById(rule.getNotificationTemplateId())
                .orElseThrow(() -> new IllegalArgumentException("Notification template was not found"));
            require(template.isActive(), "Notification template must be active");
            require(template.getChannel() == channel,
                "Notification template channel must match the action");
        }
        NotificationRecipientMode mode = rule.getNotificationRecipientMode() != null
            ? rule.getNotificationRecipientMode() : NotificationRecipientMode.FIXED;
        if (mode == NotificationRecipientMode.EVENT_FIELD) {
            require(rule.getNotificationRecipientField() != null
                    && rule.getNotificationRecipientField().matches("(?:data\\.)?[A-Za-z][A-Za-z0-9_.-]*"),
                "Notification recipient field must be a data field path");
            return;
        }
        List<String> recipients = rule.getNotificationRecipients();
        require(recipients != null && !recipients.isEmpty(),
            "At least one notification recipient is required");
        Pattern pattern = channel == NotificationChannel.EMAIL
            ? Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
            : Pattern.compile("^\\+[1-9]\\d{7,14}$");
        for (String recipient : recipients) {
            require(recipient != null && pattern.matcher(recipient.trim()).matches(),
                channel == NotificationChannel.EMAIL
                    ? "Invalid email notification recipient"
                    : "SMS notification recipients must use E.164 format");
        }
        if (channel == NotificationChannel.SMS) {
            require(recipients.size() == 1, "SMS rules support one recipient per notification");
        }
    }

    private void validateAbsence(Rule rule) {
        require(rule.getEventType() != null && !rule.getEventType().isBlank(),
            "Absence starting event type is required");
        require(rule.getSource() != null && !rule.getSource().isBlank(),
            "Absence starting source is required");
        validateExpression(rule.getConditionScript(), "Absence starting condition");
        require(rule.getExpectedEventType() != null && !rule.getExpectedEventType().isBlank(),
            "Absence expected event type is required");
        require(rule.getExpectedSource() != null && !rule.getExpectedSource().isBlank(),
            "Absence expected source is required");
        validateExpression(rule.getExpectedConditionScript(), "Absence expected condition");
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
