package com.tengencorp.tengen;

import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.WebhookOutbox;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.repository.RuleEventRepository;
import com.tengencorp.tengen.repository.WebhookOutboxRepository;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.WebhookOutboxStatus;
import com.tengencorp.tengen.service.WebhookClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the event-processing API. These require a running
 * PostgreSQL (see docker-compose). Created per plan; execution is optional.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventProcessorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RuleRepository ruleRepository;

    @Autowired
    private RuleEventRepository ruleEventRepository;

    @Autowired
    private WebhookOutboxRepository webhookOutboxRepository;

    @MockitoBean
    private WebhookClient webhookClient;

    @BeforeEach
    void cleanUp() {
        webhookOutboxRepository.deleteAll();
        ruleEventRepository.deleteAll();
        ruleRepository.deleteAll();
    }

    private Rule conditionRule(String name, String eventType, String source, String condition, boolean active) {
        Rule rule = new Rule();
        rule.setName(name);
        rule.setRuleType(RuleType.CONDITION);
        rule.setAction(RuleAction.LOG);
        rule.setEventType(eventType);
        rule.setSource(source);
        rule.setConditionScript(condition);
        rule.setActive(active);
        return ruleRepository.save(rule);
    }

    private Rule aggregateRule(String name, String eventType, String source, String condition,
                               AggregateType aggType, String aggField, double threshold, int windowSeconds) {
        return aggregateRule(name, eventType, source, condition, aggType, aggField, null, threshold, windowSeconds);
    }

    private Rule aggregateRule(String name, String eventType, String source, String condition,
                               AggregateType aggType, String aggField, String groupBy,
                               double threshold, int windowSeconds) {
        Rule rule = new Rule();
        rule.setName(name);
        rule.setRuleType(RuleType.AGGREGATE);
        rule.setAction(RuleAction.LOG);
        rule.setEventType(eventType);
        rule.setSource(source);
        rule.setConditionScript(condition);
        rule.setAggType(aggType);
        rule.setAggField(aggField);
        rule.setGroupBy(groupBy);
        rule.setThreshold(threshold);
        rule.setWindowSeconds(windowSeconds);
        rule.setActive(true);
        return ruleRepository.save(rule);
    }

    private String eventJson(String type, String source, double amount, String country) {
        return eventJsonAt(type, source, amount, country, "2026-07-31T15:30:00Z");
    }

    private String eventJsonAt(String type, String source, double amount, String country, String timestamp) {
        return """
            {
              "type": "%s",
              "source": "%s",
              "timestamp": "%s",
              "data": {
                "amount": %s,
                "country": "%s"
              }
            }
            """.formatted(type, source, timestamp, amount, country);
    }

    private String keyedEventJson(String type, String source, String userId, double amount) {
        return """
            {
              "type": "%s",
              "source": "%s",
              "timestamp": "2026-07-31T15:30:00Z",
              "data": {
                "userId": "%s",
                "amount": %s
              }
            }
            """.formatted(type, source, userId, amount);
    }

    private String jsonString(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    @Test
    void keyedAggregateKeepsGroupWindowsSeparate() throws Exception {
        aggregateRule("keyed-sum", "transaction", "payment-api", "data.amount >= 0",
            AggregateType.SUM, "data.amount", "data.userId", 1000, 300);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(keyedEventJson("transaction", "payment-api", "alice", 700)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(keyedEventJson("transaction", "payment-api", "bob", 900)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(keyedEventJson("transaction", "payment-api", "alice", 400)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.aggregates['keyed-sum'].value").value(1100.0))
            .andExpect(jsonPath("$.aggregates['keyed-sum'].groupKey").value("alice"));
    }

    @Test
    void keyedAggregateIgnoresEventsWithoutGroupKey() throws Exception {
        aggregateRule("keyed-missing", "transaction", "payment-api", "data.amount >= 0",
            AggregateType.SUM, "data.amount", "userId", 1, 300);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false))
            .andExpect(jsonPath("$.rules").isEmpty());
    }

    @Test
    @WithMockUser
    void keyedRuleTesterIncludesCandidateForItsGroupWithoutPersistingIt() throws Exception {
        Rule rule = aggregateRule("keyed-tester", "transaction", "payment-api", "data.amount >= 0",
            AggregateType.SUM, "data.amount", "data.userId", 1000, 300);
        long rowsBefore = ruleEventRepository.count();

        mockMvc.perform(post("/api/rules/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mode": "single",
                      "ruleId": %d,
                      "eventJson": "%s"
                    }
                    """.formatted(rule.getId(), jsonString(keyedEventJson("transaction", "payment-api", "alice", 1200)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.aggregateValue").value(1200.0))
            .andExpect(jsonPath("$.groupKey").value("alice"));

        assertThat(ruleEventRepository.count()).isEqualTo(rowsBefore);
    }

    @ParameterizedTest
    @CsvSource({
        "SUM,1500.0",
        "AVG,1500.0",
        "MIN,1500.0",
        "MAX,1500.0"
    })
    void nonCountAggregatesResolveDataPrefixedFields(AggregateType aggregateType, double expectedValue) throws Exception {
        aggregateRule("aggregate-" + aggregateType.name().toLowerCase(), "transaction", "payment-api",
            "data.amount >= 1000", aggregateType, "data.amount", 1500, 300);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.aggregates['aggregate-" + aggregateType.name().toLowerCase() + "'].value")
                .value(expectedValue));
    }

    @Test
    void aggregateRuleAlsoResolvesCanonicalFieldWithoutDataPrefix() throws Exception {
        aggregateRule("canonical-amount", "transaction", "payment-api",
            "data.amount >= 1000", AggregateType.SUM, "amount", 1500, 300);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.aggregates['canonical-amount'].value").value(1500.0));
    }

    @Test
    void lateEventDoesNotIncludeFutureEventsInItsWindow() throws Exception {
        aggregateRule("ordered-count", "transaction", "payment-api",
            "data.amount >= 0", AggregateType.COUNT, null, 2, 300);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJsonAt("transaction", "payment-api", 1, "PH", "2026-07-31T15:35:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJsonAt("transaction", "payment-api", 1, "PH", "2026-07-31T15:30:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));
    }

    @Test
    @WithMockUser
    void ruleTesterIncludesCandidateInAggregateWithoutPersistingIt() throws Exception {
        Rule rule = aggregateRule("tester-sum", "transaction", "payment-api",
            "data.amount >= 1000", AggregateType.SUM, "data.amount", 1000, 300);
        long rowsBefore = ruleEventRepository.count();

        mockMvc.perform(post("/api/rules/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mode": "single",
                      "ruleId": %d,
                      "eventJson": "%s"
                    }
                    """.formatted(rule.getId(), jsonString(eventJson("transaction", "payment-api", 1500, "PH")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.aggregateValue").value(1500.0));

        assertThat(ruleEventRepository.count()).isEqualTo(rowsBefore);
    }

    @Test
    void conditionRuleMatchesWhenConditionTrue() throws Exception {
        conditionRule("large-transaction", "transaction", "payment-api",
            "data.amount >= 1000 && data.country == 'PH'", true);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"))
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.rules[0]").value("large-transaction"))
            .andExpect(jsonPath("$.aggregates").isEmpty());
    }

    @Test
    void conditionRuleDoesNotMatchWhenConditionFalse() throws Exception {
        conditionRule("large-transaction", "transaction", "payment-api",
            "data.amount >= 1000 && data.country == 'PH'", true);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false))
            .andExpect(jsonPath("$.rules").isEmpty());
    }

    @Test
    void aggregateRuleFiresWhenWindowThresholdMet() throws Exception {
        aggregateRule("big-amount-sum", "transaction", "payment-api",
            "data.amount >= 1000", AggregateType.SUM, "data.amount", 4000, 300);

        // First event: 1500
        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        // Second event: 3000 -> cumulative 4500 >= 4000
        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 3000, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.rules[0]").value("big-amount-sum"))
            .andExpect(jsonPath("$.aggregates['big-amount-sum'].function").value("SUM"))
            .andExpect(jsonPath("$.aggregates['big-amount-sum'].value").value(4500.0))
            .andExpect(jsonPath("$.aggregates['big-amount-sum'].threshold").value(4000.0))
            .andExpect(jsonPath("$.aggregates['big-amount-sum'].windowSeconds").value(300));
    }

    @Test
    void noRuleMatchReturns200WithEmptyResult() throws Exception {
        conditionRule("other-source", "transaction", "other-api",
            "data.amount >= 1000", true);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 9999, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"))
            .andExpect(jsonPath("$.matched").value(false))
            .andExpect(jsonPath("$.rules").isEmpty())
            .andExpect(jsonPath("$.aggregates").isEmpty());
    }

    @Test
    void webhookRuleQueuesPayloadOnMatch() throws Exception {
        Rule rule = new Rule();
        rule.setName("webhook-rule");
        rule.setRuleType(RuleType.CONDITION);
        rule.setAction(RuleAction.WEBHOOK);
        rule.setCallbackUrl("https://example.com/hooks/events");
        rule.setEventType("transaction");
        rule.setSource("payment-api");
        rule.setConditionScript("data.amount >= 1000");
        rule.setActive(true);
        ruleRepository.save(rule);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.queuedRules[0]").value("webhook-rule"));

        assertThat(webhookOutboxRepository.findAll()).hasSize(1);
        WebhookOutbox outbox = webhookOutboxRepository.findAll().getFirst();
        assertThat(outbox.getStatus()).isEqualTo(WebhookOutboxStatus.PENDING);
        assertThat(outbox.getCallbackUrl()).isEqualTo("https://example.com/hooks/events");
        verify(webhookClient, never()).deliver(eq("https://example.com/hooks/events"), anyMap());
    }

    @Test
    void webhookNotDispatchedWhenNoMatch() throws Exception {
        Rule rule = new Rule();
        rule.setName("webhook-rule");
        rule.setRuleType(RuleType.CONDITION);
        rule.setAction(RuleAction.WEBHOOK);
        rule.setCallbackUrl("https://example.com/hooks/events");
        rule.setEventType("transaction");
        rule.setSource("payment-api");
        rule.setConditionScript("data.amount >= 1000");
        rule.setActive(true);
        ruleRepository.save(rule);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        verify(webhookClient, never()).deliver(eq("https://example.com/hooks/events"), anyMap());
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "source": "payment-api",
                      "data": {}
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void inactiveRuleDoesNotMatch() throws Exception {
        conditionRule("inactive-rule", "transaction", "payment-api",
            "data.amount >= 1", false);

        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 9999, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false))
            .andExpect(jsonPath("$.rules").isEmpty());

        assertThat(ruleRepository.findAll()).hasSize(1);
    }
}
