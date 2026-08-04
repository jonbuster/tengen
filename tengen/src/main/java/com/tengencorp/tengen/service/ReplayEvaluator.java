package com.tengencorp.tengen.service;

import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.tengencorp.tengen.dto.ReplayAggregateResult;
import com.tengencorp.tengen.dto.RuleSnapshot;
import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.helper.RuleEvaluationSupport;
import com.tengencorp.tengen.repository.ReplayJobJdbcRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** Isolated evaluator that only writes replay-owned aggregate state. */
@Service
public class ReplayEvaluator {

    private final AviatorEvaluatorInstance aviator;
    private final ReplayJobJdbcRepository jdbcRepository;

    public ReplayEvaluator(AviatorEvaluatorInstance aviator,
                           ReplayJobJdbcRepository jdbcRepository) {
        this.aviator = aviator;
        this.jdbcRepository = jdbcRepository;
    }

    public ReplayEvaluation evaluate(Long jobId, RuleSnapshot snapshot,
                                     ReplayJobJdbcRepository.ReplayInput input) {
        String groupKey = snapshot.ruleType() == RuleType.AGGREGATE
            ? RuleEvaluationSupport.extractGroupKey(input.data(), snapshot.groupBy()) : null;
        if (!matchesRoute(snapshot, input)) {
            return new ReplayEvaluation(false, groupKey, null, null);
        }

        boolean condition;
        try {
            Map<String, Object> environment = RuleEvaluationSupport.buildEnvironment(
                input.type(), input.source(), input.occurredAt(), input.data());
            condition = Boolean.TRUE.equals(aviator.execute(snapshot.conditionScript(), environment));
        } catch (Exception exception) {
            return new ReplayEvaluation(false, groupKey, null, "EXPRESSION_ERROR");
        }
        if (!condition) {
            return new ReplayEvaluation(false, groupKey, null, null);
        }

        if (snapshot.ruleType() == RuleType.CONDITION) {
            return new ReplayEvaluation(true, null, null, null);
        }

        if (usesGrouping(snapshot) && groupKey == null) {
            return new ReplayEvaluation(false, null, null, "MISSING_GROUP_KEY");
        }
        if (usesGrouping(snapshot)
                && groupKey.length() > RuleEvaluationSupport.MAX_GROUP_KEY_LENGTH) {
            return new ReplayEvaluation(false, null, null, "GROUP_KEY_TOO_LONG");
        }

        Double value = RuleEvaluationSupport.extractNumericValue(input.data(), snapshot.aggField());
        if (snapshot.aggType() != AggregateType.COUNT && value == null) {
            return new ReplayEvaluation(false, groupKey, null, "NON_NUMERIC_VALUE");
        }

        jdbcRepository.insertRuleEvent(jobId, input.position(), input.occurredAt(), groupKey, value);
        Instant since = input.occurredAt().minusSeconds(snapshot.windowSeconds());
        double aggregateValue = jdbcRepository.aggregate(
            jobId, snapshot.aggType().name(), since, input.occurredAt(), input.position(), groupKey);
        ReplayAggregateResult aggregate = new ReplayAggregateResult(
            snapshot.ruleType().name(),
            snapshot.aggType().name(),
            aggregateValue,
            snapshot.threshold(),
            snapshot.windowSeconds(),
            groupKey);
        return new ReplayEvaluation(
            aggregateValue >= snapshot.threshold(), groupKey, aggregate, null);
    }

    private boolean matchesRoute(RuleSnapshot snapshot,
                                 ReplayJobJdbcRepository.ReplayInput input) {
        return snapshot.eventType().equals(input.type()) && snapshot.source().equals(input.source());
    }

    private boolean usesGrouping(RuleSnapshot snapshot) {
        return snapshot.groupBy() != null && !snapshot.groupBy().isBlank();
    }

    public record ReplayEvaluation(boolean matched, String groupKey,
                                   ReplayAggregateResult aggregate,
                                   String errorCategory) {
    }
}
