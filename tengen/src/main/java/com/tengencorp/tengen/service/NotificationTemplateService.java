package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.NotificationTemplateRequest;
import com.tengencorp.tengen.dto.NotificationTemplateResponse;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationTemplate;
import com.tengencorp.tengen.repository.NotificationTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Creates immutable, validated notification template versions. */
@Service
public class NotificationTemplateService {

    private final NotificationTemplateRepository repository;
    private final NotificationTemplateRenderer renderer;

    public NotificationTemplateService(NotificationTemplateRepository repository,
                                       NotificationTemplateRenderer renderer) {
        this.repository = repository;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> list(NotificationChannel channel) {
        List<NotificationTemplate> templates = channel == null
            ? repository.findAllByOrderByNameAscVersionDesc()
            : repository.findByChannelAndActiveTrueOrderByNameAscVersionDesc(channel);
        return templates.stream().map(NotificationTemplateResponse::from).toList();
    }

    @Transactional
    public NotificationTemplateResponse create(NotificationTemplateRequest request) {
        NotificationTemplate template = new NotificationTemplate();
        template.setName(request.name().trim());
        template.setChannel(request.channel());
        template.setVersion(repository.findFirstByNameOrderByVersionDesc(template.getName())
            .map(existing -> existing.getVersion() + 1).orElse(1));
        template.setSubjectTemplate(blankToNull(request.subjectTemplate()));
        template.setTextTemplate(request.textTemplate());
        template.setHtmlTemplate(blankToNull(request.htmlTemplate()));
        template.setCssTemplate(blankToNull(request.cssTemplate()));
        template.setActive(true);
        renderer.validate(template);
        return NotificationTemplateResponse.from(repository.save(template));
    }

    @Transactional(readOnly = true)
    public NotificationTemplate findActive(Long id, NotificationChannel channel) {
        NotificationTemplate template = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Notification template " + id + " not found"));
        if (!template.isActive()) {
            throw new IllegalArgumentException("Notification template is inactive");
        }
        if (template.getChannel() != channel) {
            throw new IllegalArgumentException("Notification template channel does not match the rule action");
        }
        return template;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
