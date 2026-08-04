package com.tengencorp.tengen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO for POST /api/rules/test.
 *
 * @param mode      "single" runs one saved rule, "all" runs every active rule
 * @param ruleId    required in single mode
 * @param eventJson raw JSON event to evaluate
 * @param sequenceEventJsons optional ordered events for side-effect-free sequence simulation
 */
public record RuleTestRequest(
        @NotBlank(message = "mode is required")
        @Pattern(regexp = "single|all", message = "mode must be 'single' or 'all'")
        String mode,

        Long ruleId,

        @Size(max = 1048576, message = "eventJson must be at most 1048576 characters")
        String eventJson,

        @Size(max = 1048576, message = "absenceExpectedEventJson must be at most 1048576 characters")
        String absenceExpectedEventJson,

        @Size(max = 5, message = "At most five sequence events may be supplied")
        List<@Size(max = 1048576, message = "Sequence event JSON must be at most 1048576 characters") String>
        sequenceEventJsons) {
}
