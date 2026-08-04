package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.Rule;

import java.util.List;

/**
 * Response body for POST /api/rules/test.
 *
 * <p>Single mode populates {@code rule}, {@code matched},
 * {@code conditionMatched} and {@code aggregateValue}. All mode populates
 * {@code results} and {@code anyMatched}. {@code event} is always present.
 */
public record RuleTestResponse(
        RuleResponse rule,
        Boolean matched,
        Boolean conditionMatched,
        Double aggregateValue,
        String groupKey,
        Event event,
        SequenceTestResult sequenceTest,
        AbsenceTestResult absenceTest,
        List<RuleResult> results,
        Boolean anyMatched) {

    public static RuleTestResponse single(Rule rule, boolean matched, boolean conditionMatched,
                                          Double aggregateValue, String groupKey, Event event) {
        return new RuleTestResponse(RuleResponse.from(rule), matched, conditionMatched,
            aggregateValue, groupKey, event, null, null, null, null);
    }

    public static RuleTestResponse singleSequence(Rule rule, SequenceTestResult sequenceTest,
                                                  Event event) {
        return new RuleTestResponse(RuleResponse.from(rule), sequenceTest.matched(),
            sequenceTest.steps().stream().anyMatch(SequenceStepTestResult::conditionMatched),
            null, sequenceTest.groupKey(), event, sequenceTest, null, null, null);
    }

    public static RuleTestResponse singleAbsence(Rule rule, AbsenceTestResult absenceTest,
                                                 Event event) {
        return new RuleTestResponse(RuleResponse.from(rule),
            "WOULD_BE_SATISFIED".equals(absenceTest.outcome()),
            absenceTest.startMatched(),
            null,
            absenceTest.groupKey(),
            event,
            null,
            absenceTest,
            null,
            null);
    }

    public static RuleTestResponse all(List<RuleResult> results, boolean anyMatched, Event event) {
        return new RuleTestResponse(null, null, null, null, null, event, null, null, results, anyMatched);
    }
}
