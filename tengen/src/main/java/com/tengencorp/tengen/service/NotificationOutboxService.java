package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.AggregateResult;
import com.tengencorp.tengen.dto.SequenceResult;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationOutbox;
import com.tengencorp.tengen.entity.NotificationOutboxStatus;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.TriggerMode;
import com.tengencorp.tengen.repository.NotificationOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders and persists email/SMS intents without making provider calls. */
@Service
public class NotificationOutboxService {

    private static final String GLOBAL_SCOPE = "";
    private static final int MAX_SMS_CHARACTERS = 1600;

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationDestinationService destinationService;
    private final NotificationTemplateService templateService;
    private final NotificationTemplateRenderer templateRenderer;
    private final NotificationRecipientResolver recipientResolver;

    public NotificationOutboxService(NotificationOutboxRepository outboxRepository,
                                     NotificationDestinationService destinationService,
                                     NotificationTemplateService templateService,
                                     NotificationTemplateRenderer templateRenderer,
                                     NotificationRecipientResolver recipientResolver) {
        this.outboxRepository = outboxRepository;
        this.destinationService = destinationService;
        this.templateService = templateService;
        this.templateRenderer = templateRenderer;
        this.recipientResolver = recipientResolver;
    }

    @Transactional
    public EnqueueResult enqueue(Rule rule, Event event, AggregateResult aggregateResult,
                                 SequenceResult sequenceResult, String groupKey,
                                 Instant windowStart) {
        NotificationChannel channel = rule.getAction() == RuleAction.EMAIL
            ? NotificationChannel.EMAIL : NotificationChannel.SMS;
        String scopeKey = groupKey != null ? groupKey : GLOBAL_SCOPE;
        TriggerMode triggerMode = rule.getEffectiveTriggerMode();
        String deduplicationKey = deduplicationKey(rule, event, scopeKey, triggerMode, windowStart);
        var existing = outboxRepository.findByDeduplicationKey(deduplicationKey);
        if (existing.isPresent()) {
            NotificationOutbox outbox = existing.get();
            return new EnqueueResult(outbox, false,
                outbox.getStatus() == NotificationOutboxStatus.TEMPLATE_RENDER_ERROR);
        }

        NotificationDestinationView destination;
        try {
            var configuredDestination = destinationService.findEnabled(
                rule.getNotificationDestinationId(), channel);
            var template = templateService.findActive(rule.getNotificationTemplateId(), channel);
            List<String> recipients = recipientResolver.resolve(rule, event, channel);
            var rendered = templateRenderer.render(template, event, rule, groupKey);
            if (channel == NotificationChannel.SMS
                    && rendered.textBody().codePointCount(0, rendered.textBody().length()) > MAX_SMS_CHARACTERS) {
                throw new IllegalArgumentException(
                    "SMS template renders to more than " + MAX_SMS_CHARACTERS + " characters");
            }
            destination = new NotificationDestinationView(
                configuredDestination.getId(), configuredDestination.getProvider());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("recipients", recipients);
            if (channel == NotificationChannel.EMAIL) {
                message.put("subject", rendered.subject());
                message.put("textBody", rendered.textBody());
                message.put("htmlBody", rendered.htmlBody());
            } else {
                message.put("body", rendered.textBody());
            }
            NotificationOutbox outbox = new NotificationOutbox(
                event,
                rule.getId(),
                rule.getName(),
                destination.id(),
                channel,
                destination.provider(),
                message,
                scopeKey,
                triggerMode,
                windowStart,
                rule.getEffectiveRevision(),
                rule.getCooldownSeconds(),
                deduplicationKey);
            return new EnqueueResult(outboxRepository.save(outbox), true, false);
        } catch (IllegalArgumentException exception) {
            NotificationOutbox outbox = new NotificationOutbox(
                event,
                rule.getId(),
                rule.getName(),
                rule.getNotificationDestinationId(),
                channel,
                "UNRESOLVED",
                errorSnapshot(exception),
                scopeKey,
                triggerMode,
                windowStart,
                rule.getEffectiveRevision(),
                rule.getCooldownSeconds(),
                deduplicationKey);
            outbox.setStatus(NotificationOutboxStatus.TEMPLATE_RENDER_ERROR);
            outbox.setLastError(truncate(exception.getMessage()));
            return new EnqueueResult(outboxRepository.save(outbox), true, true);
        }
    }

    private Map<String, Object> errorSnapshot(IllegalArgumentException exception) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("error", truncate(exception.getMessage()));
        snapshot.put("recipients", List.of());
        return snapshot;
    }

    private String deduplicationKey(Rule rule, Event event, String scopeKey,
                                    TriggerMode triggerMode, Instant windowStart) {
        String encodedScope = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(scopeKey.getBytes(StandardCharsets.UTF_8));
        return "NOTIFICATION:" + rule.getAction().name() + ":" + switch (triggerMode) {
            case EVERY_MATCH -> "EVERY_MATCH:rule=" + rule.getId() + ":revision="
                + rule.getEffectiveRevision() + ":event=" + event.getId();
            case EDGE -> "EDGE:rule=" + rule.getId() + ":revision=" + rule.getEffectiveRevision()
                + ":event=" + event.getId() + ":scope=" + encodedScope;
            case ONCE_PER_WINDOW -> "ONCE_PER_WINDOW:rule=" + rule.getId()
                + ":revision=" + rule.getEffectiveRevision()
                + ":window=" + (windowStart != null ? windowStart.getEpochSecond() : "none")
                + ":scope=" + encodedScope;
        };
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "Notification rendering failed";
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private record NotificationDestinationView(Long id, String provider) {
    }

    public record EnqueueResult(NotificationOutbox outbox, boolean created, boolean terminalError) {
    }
}
