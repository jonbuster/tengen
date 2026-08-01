package com.tengencorp.tengen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for POST /api/rules/test.
 *
 * @param mode      "single" runs one saved rule, "all" runs every active rule
 * @param ruleId    required in single mode
 * @param eventJson raw JSON event to evaluate
 */
public record RuleTestRequest(
        @NotBlank(message = "mode is required")
        @Pattern(regexp = "single|all", message = "mode must be 'single' or 'all'")
        String mode,

        Long ruleId,

        @NotBlank(message = "eventJson is required")
        String eventJson) {
}
