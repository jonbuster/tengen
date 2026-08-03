package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.RuleSequenceStep;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One ordered event-matching step in a sequence rule. */
public record SequenceStep(
        @NotNull(message = "Sequence step position is required")
        @Min(value = 1, message = "Sequence step position must be positive")
        Integer position,

        @NotBlank(message = "Sequence step event type is required")
        @Size(max = 100, message = "Sequence step event type must be at most 100 characters")
        String eventType,

        @NotBlank(message = "Sequence step source is required")
        @Size(max = 100, message = "Sequence step source must be at most 100 characters")
        String source,

        @NotBlank(message = "Sequence step condition is required")
        String conditionScript) {

    public static SequenceStep from(RuleSequenceStep step) {
        return new SequenceStep(
            step.getPosition(),
            step.getEventType(),
            step.getSource(),
            step.getConditionScript());
    }
}
