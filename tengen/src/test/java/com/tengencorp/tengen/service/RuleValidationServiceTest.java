package com.tengencorp.tengen.service;

import com.googlecode.aviator.AviatorEvaluator;
import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.RuleSequenceStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class RuleValidationServiceTest {

    private RuleValidationService validationService;

    @BeforeEach
    void setUp() {
        var aviator = AviatorEvaluator.newInstance();
        aviator.enableSandboxMode();
        validationService = new RuleValidationService(
            aviator, new WebhookDestinationValidator(), 10_000);
    }

    @Test
    void aggregateRequiresAWindowAndType() {
        Rule rule = baseRule();
        rule.setRuleType(RuleType.AGGREGATE);

        assertThatThrownBy(() -> validationService.validate(rule))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Aggregate type");
    }

    @Test
    void nonCountAggregateRequiresAField() {
        Rule rule = baseRule();
        rule.setRuleType(RuleType.AGGREGATE);
        rule.setAggType(AggregateType.SUM);
        rule.setWindowSeconds(60);

        assertThatThrownBy(() -> validationService.validate(rule))
            .hasMessageContaining("aggregate field");
    }

    @Test
    void webhookMustUsePublicHttpsSyntax() {
        Rule rule = baseRule();
        rule.setAction(RuleAction.WEBHOOK);
        rule.setCallbackUrl("http://127.0.0.1/hook");

        assertThatThrownBy(() -> validationService.validate(rule))
            .hasMessageContaining("HTTPS");
    }

    @Test
    void invalidExpressionIsRejectedAtMutationTime() {
        Rule rule = baseRule();
        rule.setConditionScript("data.amount >=");

        assertThatThrownBy(() -> validationService.validate(rule))
            .hasMessageContaining("not valid Aviator");
    }

    @Test
    void sequenceRequiresBetweenTwoAndFiveConsecutiveSteps() {
        Rule rule = baseRule();
        rule.setRuleType(RuleType.SEQUENCE);
        rule.setEventType(null);
        rule.setSource(null);
        rule.setConditionScript(null);
        rule.setWindowSeconds(300);
        rule.getSequenceSteps().add(new RuleSequenceStep(rule, 1, "a", "source", "true"));

        assertThatThrownBy(() -> validationService.validate(rule))
            .hasMessageContaining("between 2 and 5");

        rule.getSequenceSteps().add(new RuleSequenceStep(rule, 2, "b", "source", "true"));
        validationService.validate(rule);
        assertThat(rule.getSequenceSteps()).hasSize(2);
    }

    private Rule baseRule() {
        Rule rule = new Rule();
        rule.setName("test-rule");
        rule.setRuleType(RuleType.CONDITION);
        rule.setAction(RuleAction.LOG);
        rule.setEventType("payment");
        rule.setSource("billing");
        rule.setConditionScript("data.amount >= 10");
        rule.setThreshold(0.0);
        return rule;
    }
}
