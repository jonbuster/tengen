package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.RuleActionState;
import com.tengencorp.tengen.entity.RuleActionWindow;
import com.tengencorp.tengen.entity.WebhookOutbox;
import com.tengencorp.tengen.entity.WebhookOutboxStatus;
import com.tengencorp.tengen.repository.RuleActionStateRepository;
import com.tengencorp.tengen.repository.RuleActionWindowRepository;
import com.tengencorp.tengen.repository.WebhookOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Claims outbox rows and finalizes attempts in short database transactions. */
@Service
public class WebhookOutboxDeliveryService {

    private final WebhookOutboxRepository outboxRepository;
    private final RuleActionStateRepository stateRepository;
    private final RuleActionWindowRepository windowRepository;

    public WebhookOutboxDeliveryService(WebhookOutboxRepository outboxRepository,
                                        RuleActionStateRepository stateRepository,
                                        RuleActionWindowRepository windowRepository) {
        this.outboxRepository = outboxRepository;
        this.stateRepository = stateRepository;
        this.windowRepository = windowRepository;
    }

    @Transactional
    public List<WebhookDeliveryAttempt> claimBatch(Instant now, int batchSize,
                                                   long leaseDurationMs) {
        List<WebhookOutbox> rows = outboxRepository.findClaimable(now, batchSize);
        List<WebhookDeliveryAttempt> attempts = new ArrayList<>(rows.size());
        for (WebhookOutbox outbox : rows) {
            String leaseToken = UUID.randomUUID().toString();
            outbox.setStatus(WebhookOutboxStatus.PROCESSING);
            outbox.setLeaseToken(leaseToken);
            outbox.setLeaseExpiresAt(now.plusMillis(leaseDurationMs));
            attempts.add(new WebhookDeliveryAttempt(
                outbox.getId(),
                leaseToken,
                outbox.getRuleId(),
                outbox.getEffectiveRuleRevision(),
                outbox.getRuleName(),
                outbox.getCallbackUrl(),
                outbox.getPayload(),
                outbox.getScopeKey(),
                outbox.getTriggerMode(),
                outbox.getWindowStart(),
                outbox.getCooldownSeconds(),
                outbox.getCreatedAt(),
                (outbox.getAttemptCount() != null ? outbox.getAttemptCount() : 0) + 1));
        }
        return attempts;
    }

    @Transactional
    public boolean markDelivered(WebhookDeliveryAttempt attempt, WebhookDeliveryResult result,
                                 Instant deliveredAt) {
        var outboxOptional = outboxRepository.findByIdAndLeaseTokenAndStatus(
            attempt.outboxId(), attempt.leaseToken(), WebhookOutboxStatus.PROCESSING);
        if (outboxOptional.isEmpty()) {
            return false;
        }

        WebhookOutbox outbox = outboxOptional.get();
        outbox.setStatus(WebhookOutboxStatus.DELIVERED);
        outbox.setAttemptCount(attempt.attemptNumber());
        outbox.setLastAttemptAt(deliveredAt);
        outbox.setDeliveredAt(deliveredAt);
        outbox.setLastStatusCode(result.statusCode());
        outbox.setLastError(null);
        clearLease(outbox);

        if (outbox.getRuleId() != null
            && ((outbox.getCooldownSeconds() != null && outbox.getCooldownSeconds() > 0)
                || outbox.getTriggerMode() == com.tengencorp.tengen.entity.TriggerMode.EDGE)) {
            stateRepository.findForUpdate(outbox.getRuleId(), outbox.getEffectiveRuleRevision(),
                    outbox.getScopeKey())
                .ifPresent(state -> finalizeState(state, outbox, deliveredAt));
        }

        if (outbox.getRuleId() != null
            && outbox.getTriggerMode() == com.tengencorp.tengen.entity.TriggerMode.ONCE_PER_WINDOW
            && outbox.getWindowStart() != null) {
            windowRepository.findForUpdate(
                    outbox.getRuleId(), outbox.getEffectiveRuleRevision(),
                    outbox.getScopeKey(), outbox.getWindowStart())
                .ifPresent(window -> finalizeWindow(window, outbox, deliveredAt));
        }
        return true;
    }

    @Transactional
    public boolean markFailed(WebhookDeliveryAttempt attempt, WebhookDeliveryResult result,
                              Instant failedAt, int maxAttempts, long baseDelayMs,
                              long maxDelayMs) {
        var outboxOptional = outboxRepository.findByIdAndLeaseTokenAndStatus(
            attempt.outboxId(), attempt.leaseToken(), WebhookOutboxStatus.PROCESSING);
        if (outboxOptional.isEmpty()) {
            return false;
        }

        WebhookOutbox outbox = outboxOptional.get();
        int attemptNumber = attempt.attemptNumber();
        outbox.setAttemptCount(attemptNumber);
        outbox.setLastAttemptAt(failedAt);
        outbox.setLastStatusCode(result.statusCode());
        outbox.setLastError(formatError(result));

        if (!result.retryable() || attemptNumber >= maxAttempts) {
            outbox.setStatus(WebhookOutboxStatus.DEAD_LETTER);
            outbox.setNextAttemptAt(failedAt);
        } else {
            outbox.setStatus(WebhookOutboxStatus.RETRY_SCHEDULED);
            outbox.setNextAttemptAt(failedAt.plusMillis(backoffMillis(
                attemptNumber, baseDelayMs, maxDelayMs)));
        }
        clearLease(outbox);
        return true;
    }

    private void finalizeState(RuleActionState state, WebhookOutbox outbox, Instant deliveredAt) {
        if (!java.util.Objects.equals(state.getPendingOutboxId(), outbox.getId())) {
            return;
        }
        // A null snapshot is retained for rows created before worker metadata
        // existed; record success for those rows as a safe migration fallback.
        if (outbox.getCooldownSeconds() == null || outbox.getCooldownSeconds() > 0) {
            state.setLastSuccessfulDeliveryAt(deliveredAt);
        }
        // EDGE match state is reserved at enqueue time. Do not reassert it here:
        // a later non-match may have reset the state before this delivery finished.
        state.setPendingOutboxId(null);
    }

    private void finalizeWindow(RuleActionWindow window, WebhookOutbox outbox, Instant deliveredAt) {
        if (!java.util.Objects.equals(window.getPendingOutboxId(), outbox.getId())) {
            return;
        }
        window.setDeliveredAt(deliveredAt);
        window.setPendingOutboxId(null);
    }

    private void clearLease(WebhookOutbox outbox) {
        outbox.setLeaseToken(null);
        outbox.setLeaseExpiresAt(null);
    }

    private String formatError(WebhookDeliveryResult result) {
        String status = result.statusCode() != null ? "HTTP " + result.statusCode() + ": " : "";
        String error = result.error() != null ? result.error() : "Webhook delivery failed";
        return truncate(status + error);
    }

    private String truncate(String value) {
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private long backoffMillis(int attemptNumber, long baseDelayMs, long maxDelayMs) {
        long exponential;
        if (attemptNumber >= 63 || baseDelayMs > Long.MAX_VALUE / (1L << Math.min(attemptNumber - 1, 62))) {
            exponential = maxDelayMs;
        } else {
            exponential = baseDelayMs * (1L << Math.min(attemptNumber - 1, 62));
        }
        long capped = Math.min(maxDelayMs, Math.max(0, exponential));
        long jitterBound = Math.max(1, capped / 4);
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(jitterBound + 1);
        return Math.min(maxDelayMs, capped + jitter);
    }
}
