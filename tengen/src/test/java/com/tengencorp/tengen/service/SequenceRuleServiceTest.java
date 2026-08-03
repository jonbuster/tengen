package com.tengencorp.tengen.service;

import com.googlecode.aviator.AviatorEvaluator;
import com.tengencorp.tengen.dto.SequenceTestResult;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleSequenceStep;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.repository.RuleSequenceInstanceEventRepository;
import com.tengencorp.tengen.repository.RuleSequenceInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceRuleServiceTest {

    private SequenceRuleService service;

    @BeforeEach
    void setUp() {
        var aviator = AviatorEvaluator.newInstance();
        aviator.enableSandboxMode();
        service = new SequenceRuleService(
            aviator,
            (RuleSequenceInstanceRepository) null,
            (RuleSequenceInstanceEventRepository) null);
    }

    @Test
    void simulatesThreeOrderedCorrelatedSteps() {
        Rule rule = sequenceRule();
        Instant start = Instant.parse("2026-08-03T00:00:00Z");

        SequenceTestResult result = service.testSequence(List.of(
            event("opened", start, "alice"),
            event("approved", start.plusSeconds(30), "alice"),
            event("settled", start.plusSeconds(60), "alice")), rule);

        assertThat(result.matched()).isTrue();
        assertThat(result.correlationMatched()).isTrue();
        assertThat(result.orderingValid()).isTrue();
        assertThat(result.withinWindow()).isTrue();
        assertThat(result.steps()).allMatch(step -> step.conditionMatched());
        assertThat(result.sequence()).isNotNull();
        assertThat(result.sequence().steps()).hasSize(3);
    }

    @Test
    void rejectsWrongCorrelationAndExpiredWindow() {
        Rule rule = sequenceRule();
        Instant start = Instant.parse("2026-08-03T00:00:00Z");

        SequenceTestResult wrongGroup = service.testSequence(List.of(
            event("opened", start, "alice"),
            event("approved", start.plusSeconds(30), "bob"),
            event("settled", start.plusSeconds(60), "alice")), rule);
        SequenceTestResult expired = service.testSequence(List.of(
            event("opened", start, "alice"),
            event("approved", start.plusSeconds(30), "alice"),
            event("settled", start.plusSeconds(301), "alice")), rule);

        assertThat(wrongGroup.matched()).isFalse();
        assertThat(wrongGroup.correlationMatched()).isFalse();
        assertThat(expired.matched()).isFalse();
        assertThat(expired.withinWindow()).isFalse();
    }

    private Rule sequenceRule() {
        Rule rule = new Rule();
        rule.setRuleType(RuleType.SEQUENCE);
        rule.setWindowSeconds(300);
        rule.setGroupBy("data.userId");
        rule.getSequenceSteps().add(new RuleSequenceStep(rule, 1, "opened", "workflow", "true"));
        rule.getSequenceSteps().add(new RuleSequenceStep(rule, 2, "approved", "workflow", "true"));
        rule.getSequenceSteps().add(new RuleSequenceStep(rule, 3, "settled", "workflow", "true"));
        return rule;
    }

    private Event event(String type, Instant occurredAt, String userId) {
        return new Event(type, "workflow", occurredAt, Map.of("userId", userId));
    }
}
