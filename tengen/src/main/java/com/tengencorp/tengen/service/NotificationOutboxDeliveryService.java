package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.NotificationOutbox;
import com.tengencorp.tengen.entity.NotificationOutboxStatus;
import com.tengencorp.tengen.repository.NotificationDestinationRepository;
import com.tengencorp.tengen.repository.NotificationOutboxRepository;
import com.tengencorp.tengen.repository.RuleActionStateRepository;
import com.tengencorp.tengen.repository.RuleActionWindowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Claims notification rows and finalizes provider attempts in short transactions. */
@Service
public class NotificationOutboxDeliveryService {

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationDestinationRepository destinationRepository;
    private final RuleActionStateRepository stateRepository;
    private final RuleActionWindowRepository windowRepository;

    public NotificationOutboxDeliveryService(NotificationOutboxRepository outboxRepository,
                                             NotificationDestinationRepository destinationRepository,
                                             RuleActionStateRepository stateRepository,
                                             RuleActionWindowRepository windowRepository) {
        this.outboxRepository = outboxRepository;
        this.destinationRepository = destinationRepository;
        this.stateRepository = stateRepository;
        this.windowRepository = windowRepository;
    }

    @Transactional
    public List<NotificationDeliveryAttempt> claimBatch(Instant now, int batchSize,
                                                        long leaseDurationMs) {
        List<NotificationOutbox> rows = outboxRepository.findClaimable(now, batchSize);
        List<NotificationDeliveryAttempt> attempts = new ArrayList<>(rows.size());
        for (NotificationOutbox outbox : rows) {
            String leaseToken = UUID.randomUUID().toString();
            outbox.setStatus(NotificationOutboxStatus.PROCESSING);
            outbox.setLeaseToken(leaseToken);
            outbox.setLeaseExpiresAt(now.plusMillis(leaseDurationMs));
            attempts.add(new NotificationDeliveryAttempt(
                outbox.getId(), leaseToken, outbox.getRuleId(), outbox.getEffectiveRuleRevision(),
                outbox.getRuleName(), outbox.getDestinationId(), outbox.getChannel(), outbox.getProvider(),
                outbox.getMessageSnapshot(), outbox.getScopeKey(), outbox.getTriggerMode(),
                outbox.getWindowStart(), outbox.getCooldownSeconds(), outbox.getCreatedAt(),
                (outbox.getAttemptCount() != null ? outbox.getAttemptCount() : 0) + 1));
        }
        return attempts;
    }

    @Transactional(readOnly = true)
    public com.tengencorp.tengen.entity.NotificationDestination destination(Long id) {
        return destinationRepository.findById(id).orElse(null);
    }

    @Transactional
    public boolean markSubmitted(NotificationDeliveryAttempt attempt,
                                 NotificationProviderResult result, Instant submittedAt) {
        var optional = outboxRepository.findByIdAndLeaseTokenAndStatus(
            attempt.outboxId(), attempt.leaseToken(), NotificationOutboxStatus.PROCESSING);
        if (optional.isEmpty()) {
            return false;
        }
        NotificationOutbox outbox = optional.get();
        outbox.setStatus(NotificationOutboxStatus.SUBMITTED);
        outbox.setAttemptCount(attempt.attemptNumber());
        outbox.setLastAttemptAt(submittedAt);
        outbox.setSubmittedAt(submittedAt);
        outbox.setProviderMessageId(result.providerMessageId());
        outbox.setLastError(null);
        clearLease(outbox);
        finalizeTriggerState(outbox, submittedAt);
        return true;
    }

    @Transactional
    public boolean markFailed(NotificationDeliveryAttempt attempt,
                              NotificationProviderResult result, Instant failedAt,
                              int maxAttempts, long baseDelayMs, long maxDelayMs) {
        var optional = outboxRepository.findByIdAndLeaseTokenAndStatus(
            attempt.outboxId(), attempt.leaseToken(), NotificationOutboxStatus.PROCESSING);
        if (optional.isEmpty()) {
            return false;
        }
        NotificationOutbox outbox = optional.get();
        int attemptNumber = attempt.attemptNumber();
        outbox.setAttemptCount(attemptNumber);
        outbox.setLastAttemptAt(failedAt);
        outbox.setLastError(formatError(result));
        if (!result.retryable() || attemptNumber >= maxAttempts) {
            outbox.setStatus(NotificationOutboxStatus.DEAD_LETTER);
            outbox.setNextAttemptAt(failedAt);
        } else {
            outbox.setStatus(NotificationOutboxStatus.RETRY_SCHEDULED);
            outbox.setNextAttemptAt(failedAt.plusMillis(backoffMillis(
                attemptNumber, baseDelayMs, maxDelayMs)));
        }
        clearLease(outbox);
        return true;
    }

    private void finalizeTriggerState(NotificationOutbox outbox, Instant completedAt) {
        if (outbox.getRuleId() != null
                && ((outbox.getCooldownSeconds() != null && outbox.getCooldownSeconds() > 0)
                    || outbox.getTriggerMode() == com.tengencorp.tengen.entity.TriggerMode.EDGE)) {
            stateRepository.findForUpdate(outbox.getRuleId(), outbox.getEffectiveRuleRevision(),
                    outbox.getScopeKey())
                .ifPresent(state -> {
                    if (Objects.equals(state.getPendingOutboxId(), outbox.getId())) {
                        state.setLastSuccessfulDeliveryAt(completedAt);
                        state.setPendingOutboxId(null);
                    }
                });
        }
        if (outbox.getRuleId() != null
                && outbox.getTriggerMode() == com.tengencorp.tengen.entity.TriggerMode.ONCE_PER_WINDOW
                && outbox.getWindowStart() != null) {
            windowRepository.findForUpdate(outbox.getRuleId(), outbox.getEffectiveRuleRevision(),
                    outbox.getScopeKey(), outbox.getWindowStart())
                .ifPresent(window -> {
                    if (Objects.equals(window.getPendingOutboxId(), outbox.getId())) {
                        window.setDeliveredAt(completedAt);
                        window.setPendingOutboxId(null);
                    }
                });
        }
    }

    private void clearLease(NotificationOutbox outbox) {
        outbox.setLeaseToken(null);
        outbox.setLeaseExpiresAt(null);
    }

    private String formatError(NotificationProviderResult result) {
        String category = result.category() != null ? result.category() + ": " : "";
        String error = result.error() != null ? result.error() : "Notification delivery failed";
        String formatted = category + error;
        return formatted.length() <= 2000 ? formatted : formatted.substring(0, 2000);
    }

    private long backoffMillis(int attemptNumber, long baseDelayMs, long maxDelayMs) {
        long exponential = attemptNumber >= 63
            ? maxDelayMs
            : Math.min(maxDelayMs, baseDelayMs * (1L << Math.min(attemptNumber - 1, 62)));
        long jitterBound = Math.max(1, exponential / 4);
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(jitterBound + 1);
        return Math.min(maxDelayMs, exponential + jitter);
    }
}
