package com.tengencorp.tengen.service;

import com.googlecode.aviator.AviatorEvaluator;
import com.tengencorp.tengen.dto.AbsenceTestResult;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAbsenceInstance;
import com.tengencorp.tengen.entity.RuleAbsenceInstanceStatus;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.repository.RuleAbsenceInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AbsenceRuleServiceTest {

    private AbsenceRuleService service;

    @BeforeEach
    void setUp() {
        var aviator = AviatorEvaluator.newInstance();
        aviator.enableSandboxMode();
        service = new AbsenceRuleService(aviator, null);
    }

    @Test
    void testReportsAnOpenAbsenceWhenExpectedEventIsMissing() {
        Rule rule = rule();
        Event start = event(10L, "payment.started", "order-1",
            Instant.parse("2026-08-04T00:00:00Z"));

        AbsenceTestResult result = service.test(start, null, rule);

        assertThat(result.startMatched()).isTrue();
        assertThat(result.outcome()).isEqualTo("WOULD_TRIGGER");
        assertThat(result.absence()).isNotNull();
        assertThat(result.absence().deadlineAt())
            .isEqualTo(Instant.parse("2026-08-04T00:01:00Z"));
    }

    @Test
    void testReportsSatisfactionForAnInWindowCorrelatedExpectedEvent() {
        Rule rule = rule();
        Event start = event(10L, "payment.started", "order-1",
            Instant.parse("2026-08-04T00:00:00Z"));
        Event expected = event(11L, "payment.completed", "order-1",
            Instant.parse("2026-08-04T00:00:30Z"));

        AbsenceTestResult result = service.test(start, expected, rule);

        assertThat(result.outcome()).isEqualTo("WOULD_BE_SATISFIED");
        assertThat(result.correlationMatched()).isTrue();
        assertThat(result.orderingValid()).isTrue();
        assertThat(result.withinWindow()).isTrue();
    }

    @Test
    void productionStartOpensOnlyOnePendingInstance() {
        Rule rule = rule();
        Event start = event(10L, "payment.started", "order-1",
            Instant.parse("2026-08-04T00:00:00Z"));
        RuleAbsenceInstanceRepository repository = repository(Optional.empty(), List.of());
        service = new AbsenceRuleService(
            AviatorEvaluator.newInstance(), repository);

        var first = service.process(start, rule);
        var second = service.process(start, rule);

        assertThat(first.opened()).isTrue();
        assertThat(second.opened()).isFalse();
    }

    private Rule rule() {
        Rule rule = new Rule();
        rule.setId(5L);
        rule.setRuleType(RuleType.ABSENCE);
        rule.setEventType("payment.started");
        rule.setSource("payments");
        rule.setConditionScript("data.status == 'started'");
        rule.setExpectedEventType("payment.completed");
        rule.setExpectedSource("payments");
        rule.setExpectedConditionScript("data.status == 'completed'");
        rule.setWindowSeconds(60);
        rule.setGroupBy("data.orderId");
        return rule;
    }

    private Event event(Long id, String type, String orderId, Instant occurredAt) {
        Event event = new Event(type, "payments", occurredAt,
            Map.of("orderId", orderId, "status", type.endsWith("started") ? "started" : "completed"));
        event.setId(id);
        return event;
    }

    private RuleAbsenceInstanceRepository repository(Optional<RuleAbsenceInstance> pending,
                                                      List<RuleAbsenceInstance> satisfiable) {
        RuleAbsenceInstance[] current = {pending.orElse(null)};
        return (RuleAbsenceInstanceRepository) Proxy.newProxyInstance(
            RuleAbsenceInstanceRepository.class.getClassLoader(),
            new Class<?>[] {RuleAbsenceInstanceRepository.class},
            (ignored, method, arguments) -> switch (method.getName()) {
                case "insertPending" -> {
                    if (current[0] == null) {
                        current[0] = new RuleAbsenceInstance();
                        current[0].setStatus(RuleAbsenceInstanceStatus.PENDING);
                        yield 1;
                    }
                    yield 0;
                }
                case "findPendingForUpdate" -> Optional.ofNullable(current[0]);
                case "findSatisfiable" -> satisfiable;
                case "save" -> {
                    current[0] = (RuleAbsenceInstance) arguments[0];
                    yield arguments[0];
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
