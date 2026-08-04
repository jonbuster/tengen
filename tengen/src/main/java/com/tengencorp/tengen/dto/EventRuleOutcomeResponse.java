package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.EventRuleActionOutcome;
import com.tengencorp.tengen.entity.EventRuleOutcome;
import com.tengencorp.tengen.entity.RuleType;
import tools.jackson.databind.ObjectMapper;

/** Admin representation of one immutable matched-rule outcome. */
public record EventRuleOutcomeResponse(
        Long id,
        Long ruleId,
        int ruleRevision,
        String ruleName,
        RuleType ruleType,
        String groupKey,
        AggregateResult aggregate,
        SequenceResult sequence,
        AbsenceResult absence,
        EventRuleActionOutcome actionOutcome,
        String suppressionReason,
        Long deliveryId) {

    public static EventRuleOutcomeResponse from(EventRuleOutcome outcome, ObjectMapper objectMapper) {
        AggregateResult aggregate = outcome.getAggregateResult() == null
            ? null : objectMapper.convertValue(outcome.getAggregateResult(), AggregateResult.class);
        SequenceResult sequence = outcome.getSequenceResult() == null
            ? null : objectMapper.convertValue(outcome.getSequenceResult(), SequenceResult.class);
        AbsenceResult absence = outcome.getAbsenceResult() == null
            ? null : objectMapper.convertValue(outcome.getAbsenceResult(), AbsenceResult.class);
        return new EventRuleOutcomeResponse(
            outcome.getId(),
            outcome.getRuleId(),
            outcome.getRuleRevision() != null ? outcome.getRuleRevision() : 1,
            outcome.getRuleName(),
            outcome.getRuleType(),
            outcome.getGroupKey(),
            aggregate,
            sequence,
            absence,
            outcome.getActionOutcome(),
            outcome.getSuppressionReason(),
            outcome.getDeliveryId());
    }
}
