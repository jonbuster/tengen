package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationTemplate;

import java.time.Instant;

public record NotificationTemplateResponse(
        Long id,
        String name,
        NotificationChannel channel,
        int version,
        String subjectTemplate,
        String textTemplate,
        String htmlTemplate,
        String cssTemplate,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static NotificationTemplateResponse from(NotificationTemplate template) {
        return new NotificationTemplateResponse(
            template.getId(),
            template.getName(),
            template.getChannel(),
            template.getVersion() != null ? template.getVersion() : 1,
            template.getSubjectTemplate(),
            template.getTextTemplate(),
            template.getHtmlTemplate(),
            template.getCssTemplate(),
            template.isActive(),
            template.getCreatedAt(),
            template.getUpdatedAt());
    }
}
