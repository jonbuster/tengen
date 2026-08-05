package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request for a new immutable notification template version. */
public record NotificationTemplateRequest(
        @NotBlank(message = "Template name is required")
        @Size(max = 100, message = "Template name must be at most 100 characters")
        String name,

        @NotNull(message = "Channel is required")
        NotificationChannel channel,

        @Size(max = 300, message = "Subject template must be at most 300 characters")
        String subjectTemplate,

        @NotBlank(message = "Text template is required")
        @Size(max = 100_000, message = "Text template is too large")
        String textTemplate,

        @Size(max = 200_000, message = "HTML template is too large")
        String htmlTemplate,

        @Size(max = 50_000, message = "CSS template is too large")
        String cssTemplate) {
}
