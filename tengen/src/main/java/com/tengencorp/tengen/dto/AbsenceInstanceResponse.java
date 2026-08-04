package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.RuleAbsenceInstance;
import com.tengencorp.tengen.entity.RuleAbsenceInstanceStatus;

import java.time.Instant;

/** Admin view of one absence expectation and its delayed lifecycle. */
public record AbsenceInstanceResponse(
        Long id,
        Long ruleId,
        int ruleRevision,
        String ruleName,
        String scopeKey,
        Long startEventId,
        Instant startOccurredAt,
        Instant deadlineAt,
        RuleAbsenceInstanceStatus status,
        Long resolvedByEventId,
        Instant resolvedAt,
        Long deliveryId,
        String suppressionReason) {

    public static AbsenceInstanceResponse from(RuleAbsenceInstance instance) {
        return new AbsenceInstanceResponse(
            instance.getId(),
            instance.getRule().getId(),
            instance.getEffectiveRuleRevision(),
            instance.getRule().getName(),
            instance.getScopeKey(),
            instance.getStartEvent().getId(),
            instance.getStartOccurredAt(),
            instance.getDeadlineAt(),
            instance.getStatus(),
            instance.getResolvedByEvent() != null ? instance.getResolvedByEvent().getId() : null,
            instance.getResolvedAt(),
            instance.getDeliveryId(),
            instance.getSuppressionReason());
    }
}
