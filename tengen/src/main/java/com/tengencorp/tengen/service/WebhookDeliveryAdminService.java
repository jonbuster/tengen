package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.WebhookDeliveryDetail;
import com.tengencorp.tengen.dto.WebhookDeliveryPage;
import com.tengencorp.tengen.dto.WebhookDeliverySummary;
import com.tengencorp.tengen.entity.WebhookOutbox;
import com.tengencorp.tengen.entity.WebhookOutboxStatus;
import com.tengencorp.tengen.exception.ConflictException;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.repository.WebhookOutboxRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/** Admin-facing queries and manual recovery for webhook deliveries. */
@Service
public class WebhookDeliveryAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final WebhookOutboxRepository outboxRepository;

    public WebhookDeliveryAdminService(WebhookOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public WebhookDeliveryPage list(int page, int size, String status, Long ruleId,
                                    Long eventId, Instant from, Instant to, String search) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be non-negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be earlier than to");
        }

        WebhookOutboxStatus parsedStatus = parseStatus(status);
        String normalizedSearch = normalize(search);
        var pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<WebhookOutbox> results = outboxRepository.search(
            parsedStatus, ruleId, eventId, from, to, normalizedSearch, pageable);
        return new WebhookDeliveryPage(
            results.map(this::summary).getContent(),
            results.getNumber(),
            results.getSize(),
            results.getTotalElements(),
            results.getTotalPages());
    }

    @Transactional(readOnly = true)
    public WebhookDeliveryDetail get(Long id) {
        WebhookOutbox outbox = outboxRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Webhook delivery " + id + " not found"));
        return detail(outbox);
    }

    @Transactional
    public WebhookDeliveryDetail retry(Long id) {
        WebhookOutbox outbox = outboxRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Webhook delivery " + id + " not found"));
        if (outbox.getStatus() != WebhookOutboxStatus.DEAD_LETTER) {
            throw new ConflictException(
                "Only DEAD_LETTER deliveries can be retried; current status is " + outbox.getStatus());
        }

        outbox.setStatus(WebhookOutboxStatus.RETRY_SCHEDULED);
        outbox.setNextAttemptAt(Instant.now());
        outbox.setLeaseToken(null);
        outbox.setLeaseExpiresAt(null);
        outbox.setManuallyRetriedAt(Instant.now());
        return detail(outboxRepository.save(outbox));
    }

    private WebhookDeliverySummary summary(WebhookOutbox outbox) {
        return WebhookDeliverySummary.from(outbox, safeDestination(outbox.getCallbackUrl()));
    }

    private WebhookDeliveryDetail detail(WebhookOutbox outbox) {
        return new WebhookDeliveryDetail(
            summary(outbox),
            safeDestination(outbox.getCallbackUrl()),
            outbox.getPayload(),
            outbox.getDeduplicationKey(),
            outbox.getLeaseExpiresAt());
    }

    private WebhookOutboxStatus parseStatus(String status) {
        String normalized = normalize(status);
        if (normalized == null) {
            return null;
        }
        try {
            return WebhookOutboxStatus.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown delivery status: " + status);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String safeDestination(String callbackUrl) {
        try {
            URI uri = URI.create(callbackUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "redacted";
            }
            String host = uri.getHost();
            String authority = uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
            String path = uri.getPath();
            return uri.getScheme() + "://" + authority
                + (path == null || path.isBlank() ? "/" : path);
        } catch (IllegalArgumentException e) {
            return "redacted";
        }
    }
}
