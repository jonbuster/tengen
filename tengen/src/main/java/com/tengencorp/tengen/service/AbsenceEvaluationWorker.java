package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.AbsenceResult;
import com.tengencorp.tengen.entity.EventRuleActionOutcome;
import com.tengencorp.tengen.entity.EventRuleOutcome;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAbsenceInstance;
import com.tengencorp.tengen.entity.RuleAbsenceInstanceStatus;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.repository.EventRuleOutcomeRepository;
import com.tengencorp.tengen.repository.RuleAbsenceInstanceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Closes event-time absence windows and persists delayed match outcomes. */
@Service
@ConditionalOnProperty(
    name = "tengen.absence.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AbsenceEvaluationWorker {

    private final RuleAbsenceInstanceRepository instanceRepository;
    private final EventWatermarkService watermarkService;
    private final WebhookCooldownService cooldownService;
    private final WebhookOutboxService outboxService;
    private final NotificationOutboxService notificationOutboxService;
    private final EventRuleOutcomeRepository outcomeRepository;
    private final int batchSize;
    private final Counter triggered;
    private final Counter cancelled;
    private final Counter queued;
    private final Counter suppressed;

    public AbsenceEvaluationWorker(
            RuleAbsenceInstanceRepository instanceRepository,
            EventWatermarkService watermarkService,
            WebhookCooldownService cooldownService,
            WebhookOutboxService outboxService,
            NotificationOutboxService notificationOutboxService,
            EventRuleOutcomeRepository outcomeRepository,
            MeterRegistry meterRegistry,
            @Value("${tengen.absence.worker.batch-size:100}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("tengen.absence.worker.batch-size must be positive");
        }
        this.instanceRepository = instanceRepository;
        this.watermarkService = watermarkService;
        this.cooldownService = cooldownService;
        this.outboxService = outboxService;
        this.notificationOutboxService = notificationOutboxService;
        this.outcomeRepository = outcomeRepository;
        this.batchSize = batchSize;
        this.triggered = meterRegistry.counter("tengen.absence.instances", "result", "triggered");
        this.cancelled = meterRegistry.counter("tengen.absence.instances", "result", "cancelled");
        this.queued = meterRegistry.counter("tengen.absence.actions", "result", "queued");
        this.suppressed = meterRegistry.counter("tengen.absence.actions", "result", "suppressed");
        Gauge.builder("tengen.absence.pending", instanceRepository,
                repository -> repository.countByStatus(RuleAbsenceInstanceStatus.PENDING))
            .description("Pending absence instances")
            .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString = "${tengen.absence.worker.poll-interval-ms:1000}",
        initialDelayString = "${tengen.absence.worker.initial-delay-ms:1000}")
    @Transactional
    public void processDueInstances() {
        Instant now = Instant.now();
        List<RuleAbsenceInstance> instances = instanceRepository.findDue(
            RuleAbsenceInstanceStatus.PENDING.name(), now, batchSize);
        for (RuleAbsenceInstance candidate : instances) {
            Rule rule = candidate.getRule();
            Instant watermark = rule != null
                    && rule.getRuleType() == com.tengencorp.tengen.entity.RuleType.ABSENCE
                    && rule.getExpectedEventType() != null
                    && rule.getExpectedSource() != null
                ? watermarkService.advanceIdle(
                    rule.getExpectedEventType(), rule.getExpectedSource(), now)
                : null;
            instanceRepository.findByIdForUpdate(candidate.getId())
                .filter(instance -> instance.getStatus() == RuleAbsenceInstanceStatus.PENDING)
                .ifPresent(instance -> closeOne(instance, now, watermark));
        }
    }

    private void closeOne(RuleAbsenceInstance instance, Instant now, Instant watermark) {
        Rule rule = instance.getRule();
        if (rule == null || rule.getRuleType() != com.tengencorp.tengen.entity.RuleType.ABSENCE
                || !rule.isActive() || rule.isArchived()
                || rule.getValidationStatus() != com.tengencorp.tengen.entity.RuleValidationStatus.VALID
                || rule.getEffectiveRevision() != instance.getEffectiveRuleRevision()) {
            instance.setStatus(RuleAbsenceInstanceStatus.CANCELLED);
            instance.setResolvedAt(now);
            instanceRepository.save(instance);
            cancelled.increment();
            return;
        }

        if (watermark == null || watermark.isBefore(instance.getDeadlineAt())) {
            return;
        }

        AbsenceResult absence = new AbsenceResult(
            instance.getId(),
            emptyToNull(instance.getScopeKey()),
            instance.getStartEvent().getId(),
            instance.getStartOccurredAt(),
            rule.getExpectedEventType(),
            rule.getExpectedSource(),
            instance.getDeadlineAt(),
            watermark);

        ActionDecision action = dispatch(rule, instance, absence);
        instance.setStatus(RuleAbsenceInstanceStatus.TRIGGERED);
        instance.setResolvedAt(now);
        instance.setDeliveryId(action.deliveryId());
        instance.setSuppressionReason(action.suppressionReason());
        instanceRepository.save(instance);

        outcomeRepository.save(new EventRuleOutcome(
            instance.getStartEvent(),
            rule.getId(),
            rule.getEffectiveRevision(),
            rule.getName(),
            rule.getRuleType(),
            emptyToNull(instance.getScopeKey()),
            null,
            null,
            absence,
            action.outcome(),
            action.suppressionReason(),
            action.deliveryId()));
        instance.getStartEvent().recordDelayedProcessingTrace(
            1,
            isQueued(action.outcome()) ? 1 : 0,
            isSuppressed(action.outcome()) ? 1 : 0);
        triggered.increment();
    }

    private ActionDecision dispatch(Rule rule, RuleAbsenceInstance instance,
                                    AbsenceResult absence) {
        if (!isExternalAction(rule)) {
            return new ActionDecision(EventRuleActionOutcome.LOG_ONLY, null, null);
        }

        String groupKey = emptyToNull(instance.getScopeKey());
        var state = rule.getCooldownSeconds() != null && rule.getCooldownSeconds() > 0
            ? cooldownService.lockState(rule, groupKey) : null;
        if (state != null) {
            if (state.getPendingOutboxId() != null
                    || cooldownService.isSuppressed(state, rule.getCooldownSeconds(), Instant.now())) {
                suppressed.increment();
                return new ActionDecision(
                    suppressedOutcome(rule),
                    state.getPendingOutboxId() != null
                        ? "COOLDOWN_ACTIVE_OR_RESERVED" : "COOLDOWN_ACTIVE",
                    null);
            }
        }

        Long deliveryId;
        boolean terminalError = false;
        if (rule.getAction() == RuleAction.WEBHOOK) {
            deliveryId = outboxService.enqueueAbsence(rule, instance.getStartEvent(), absence)
                .outbox().getId();
        } else {
            var enqueue = notificationOutboxService.enqueue(
                rule, instance.getStartEvent(), null, null, groupKey, null);
            deliveryId = enqueue.outbox().getId();
            terminalError = enqueue.terminalError();
        }
        if (terminalError) {
            return new ActionDecision(failedOutcome(rule), "TEMPLATE_RENDER_ERROR", deliveryId);
        }
        if (state != null) {
            state.setPendingOutboxId(deliveryId);
        }
        if (deliveryId != null) {
            queued.increment();
        }
        return new ActionDecision(queuedOutcome(rule), null, deliveryId);
    }

    private boolean isExternalAction(Rule rule) {
        return (rule.getAction() == RuleAction.WEBHOOK && rule.getCallbackUrl() != null)
            || ((rule.getAction() == RuleAction.EMAIL || rule.getAction() == RuleAction.SMS)
                && rule.getNotificationDestinationId() != null
                && rule.getNotificationTemplateId() != null);
    }

    private EventRuleActionOutcome queuedOutcome(Rule rule) {
        return rule.getAction() == RuleAction.WEBHOOK
            ? EventRuleActionOutcome.WEBHOOK_QUEUED
            : rule.getAction() == RuleAction.EMAIL
                ? EventRuleActionOutcome.EMAIL_QUEUED : EventRuleActionOutcome.SMS_QUEUED;
    }

    private EventRuleActionOutcome failedOutcome(Rule rule) {
        return rule.getAction() == RuleAction.EMAIL
            ? EventRuleActionOutcome.EMAIL_FAILED : EventRuleActionOutcome.SMS_FAILED;
    }

    private EventRuleActionOutcome suppressedOutcome(Rule rule) {
        return rule.getAction() == RuleAction.WEBHOOK
            ? EventRuleActionOutcome.WEBHOOK_SUPPRESSED
            : rule.getAction() == RuleAction.EMAIL
                ? EventRuleActionOutcome.EMAIL_SUPPRESSED : EventRuleActionOutcome.SMS_SUPPRESSED;
    }

    private boolean isQueued(EventRuleActionOutcome outcome) {
        return outcome == EventRuleActionOutcome.WEBHOOK_QUEUED
            || outcome == EventRuleActionOutcome.EMAIL_QUEUED
            || outcome == EventRuleActionOutcome.SMS_QUEUED;
    }

    private boolean isSuppressed(EventRuleActionOutcome outcome) {
        return outcome == EventRuleActionOutcome.WEBHOOK_SUPPRESSED
            || outcome == EventRuleActionOutcome.EMAIL_SUPPRESSED
            || outcome == EventRuleActionOutcome.SMS_SUPPRESSED;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record ActionDecision(EventRuleActionOutcome outcome,
                                  String suppressionReason, Long deliveryId) {
    }
}
