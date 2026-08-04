package com.tengencorp.tengen;

import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.WebhookOutbox;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.repository.RuleEventRepository;
import com.tengencorp.tengen.repository.WebhookOutboxRepository;
import com.tengencorp.tengen.repository.EventRepository;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.entity.WebhookOutboxStatus;
import com.tengencorp.tengen.service.ApiKeyService;
import com.tengencorp.tengen.entity.ResponseMode;
import com.tengencorp.tengen.service.WebhookDeliveryAttempt;
import com.tengencorp.tengen.service.WebhookDeliveryResult;
import com.tengencorp.tengen.service.WebhookOutboxDeliveryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the event-processing API. The database is a fresh
 * PostgreSQL container for every test class; the development database is
 * never used or cleaned by this suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "tengen.webhook.worker.enabled=false",
    "tengen.retention.enabled=false",
    "admin.password=integration-admin-password",
    "jwt.secret=integration-test-secret-with-more-than-32-bytes",
    "tengen.webhook.worker.signing-secret=integration-test-signing-secret-with-more-than-32-bytes"
})
class EventProcessorIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("test")
        .withUsername("tengen")
        .withPassword("tengen");

    @DynamicPropertySource
    static void registerDatabase(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopDatabase() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RuleRepository ruleRepository;

    @Autowired
    private RuleEventRepository ruleEventRepository;

    @Autowired
    private WebhookOutboxRepository webhookOutboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private WebhookOutboxDeliveryService webhookOutboxDeliveryService;

    @Autowired
    private ObjectMapper objectMapper;

    private String rawApiKey;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE rule_action_state, rule_action_windows, "
            + "webhook_outbox, rule_events, rule_revisions, event_idempotency, "
            + "event_stream_watermarks, events, "
            + "rules, api_keys, refresh_sessions RESTART IDENTITY CASCADE");
        // These legacy processing assertions exercise the full response shape;
        // producer-facing new keys default to COMPACT in production.
        rawApiKey = apiKeyService.create("integration", null, null, null, ResponseMode.FULL).rawKey();
    }

    private MockHttpServletRequestBuilder eventPost() {
        return eventPost(rawApiKey);
    }

    private MockHttpServletRequestBuilder eventPost(String apiKey) {
        return post("/api/events")
            .header("X-API-Key", apiKey)
            .contentType(MediaType.APPLICATION_JSON);
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

        mockMvc.perform(eventPost()
                .contentType(MediaType.APPLICATION_JSON)
                .content(keyedEventJson("transaction", "payment-api", "alice", 700)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        mockMvc.perform(eventPost()
                .contentType(MediaType.APPLICATION_JSON)
                .content(keyedEventJson("transaction", "payment-api", "bob", 900)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        mockMvc.perform(eventPost()
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

        mockMvc.perform(eventPost()
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

        mockMvc.perform(eventPost()
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

        mockMvc.perform(eventPost()
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

        mockMvc.perform(eventPost()
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJsonAt("transaction", "payment-api", 1, "PH", "2026-07-31T15:35:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        mockMvc.perform(eventPost()
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJsonAt("transaction", "payment-api", 1, "PH", "2026-07-31T15:31:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));
    }

    @Test
    void tooLateEventIsRetainedButDoesNotEvaluateRulesOrQueueActions() throws Exception {
        Rule rule = new Rule();
        rule.setName("watermark-webhook");
        rule.setRuleType(RuleType.CONDITION);
        rule.setAction(RuleAction.WEBHOOK);
        rule.setCallbackUrl("https://example.com/hooks/watermarks");
        rule.setEventType("watermark-event");
        rule.setSource("watermark-source");
        rule.setConditionScript("data.amount >= 1");
        rule.setActive(true);
        ruleRepository.save(rule);

        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:00:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"))
            .andExpect(jsonPath("$.matched").value(true));
        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:10:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"))
            .andExpect(jsonPath("$.matched").value(true));

        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:04:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"))
            .andExpect(jsonPath("$.eventTimeStatus").value("TOO_LATE"))
            .andExpect(jsonPath("$.matched").value(false))
            .andExpect(jsonPath("$.rules").isEmpty())
            .andExpect(jsonPath("$.queuedRules").isEmpty());

        assertThat(webhookOutboxRepository.findAll()).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from event_rule_outcomes", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from events where event_time_status = 'TOO_LATE'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void lateAcceptedEventContinuesThroughRuleEvaluation() throws Exception {
        conditionRule("late-accepted-condition", "watermark-event", "watermark-source",
            "data.amount >= 1", true);

        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:00:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"));
        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:10:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"));

        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:07:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("LATE_ACCEPTED"))
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.rules[0]").value("late-accepted-condition"));
    }

    @Test
    void lateAcceptedAggregateUsesItsOwnEventTimeWindow() throws Exception {
        aggregateRule("late-accepted-sum", "watermark-event", "watermark-source",
            "data.amount >= 0", AggregateType.SUM, "data.amount", 400, 300);

        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 100,
                "PH", "2026-08-03T10:00:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"))
            .andExpect(jsonPath("$.matched").value(false));
        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 100,
                "PH", "2026-08-03T10:10:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"))
            .andExpect(jsonPath("$.matched").value(false));

        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 500,
                "PH", "2026-08-03T10:07:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("LATE_ACCEPTED"))
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.aggregates['late-accepted-sum'].value").value(500.0));
    }

    @Test
    void watermarkStreamsAreIndependentBySourceAndType() throws Exception {
        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "source-a", 1,
                "PH", "2026-08-03T10:10:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"));

        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "source-b", 1,
                "PH", "2026-08-03T10:00:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"));

        mockMvc.perform(eventPost().content(eventJsonAt("other-event", "source-a", 1,
                "PH", "2026-08-03T10:00:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"));

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from event_stream_watermarks", Integer.class)).isEqualTo(3);
    }

    @Test
    void idempotentReplayDoesNotCreateOrAdvanceAnotherWatermarkEvent() throws Exception {
        String first = eventJsonAt("watermark-event", "watermark-source", 1,
            "PH", "2026-08-03T10:10:00Z");
        mockMvc.perform(eventPost().header("Idempotency-Key", "watermark-replay").content(first))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"));
        mockMvc.perform(eventPost().header("Idempotency-Key", "watermark-replay").content(first))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.eventTimeStatus").value("ON_TIME"));

        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:04:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTimeStatus").value("TOO_LATE"));

        assertThat(eventRepository.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from event_stream_watermarks", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select max(max_occurred_at) from event_stream_watermarks", Instant.class))
            .isEqualTo(Instant.parse("2026-08-03T10:10:00Z"));
    }

    @Test
    void concurrentEventsKeepTheStreamMaximumMonotonic() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> older = executor.submit(() -> mockMvc.perform(
                eventPost().content(eventJsonAt("concurrent-event", "concurrent-source", 1,
                    "PH", "2026-08-03T10:00:00Z")))
                .andReturn().getResponse().getStatus());
            Future<Integer> newer = executor.submit(() -> mockMvc.perform(
                eventPost().content(eventJsonAt("concurrent-event", "concurrent-source", 1,
                    "PH", "2026-08-03T10:10:00Z")))
                .andReturn().getResponse().getStatus());

            assertThat(older.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(newer.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "select max_occurred_at from event_stream_watermarks "
                + "where event_type = 'concurrent-event' and source = 'concurrent-source'",
            Instant.class)).isEqualTo(Instant.parse("2026-08-03T10:10:00Z"));
    }

    @Test
    @WithMockUser
    void eventHistoryCanFilterByEventTimeStatus() throws Exception {
        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:10:00Z")))
            .andExpect(status().isOk());
        mockMvc.perform(eventPost().content(eventJsonAt("watermark-event", "watermark-source", 1,
                "PH", "2026-08-03T10:04:00Z")))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/event-history")
                .param("eventTimeStatus", "TOO_LATE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].eventTimeStatus").value("TOO_LATE"))
            .andExpect(jsonPath("$.content[0].watermarkAtDecision").value("2026-08-03T10:05:00Z"));
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

        mockMvc.perform(eventPost()
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

        mockMvc.perform(eventPost()
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
        mockMvc.perform(eventPost()
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

        // Second event: 3000 -> cumulative 4500 >= 4000
        mockMvc.perform(eventPost()
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

        mockMvc.perform(eventPost()
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

        mockMvc.perform(eventPost()
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.queuedRules[0]").value("webhook-rule"));

        assertThat(webhookOutboxRepository.findAll()).hasSize(1);
        WebhookOutbox outbox = webhookOutboxRepository.findAll().getFirst();
        assertThat(outbox.getStatus()).isEqualTo(WebhookOutboxStatus.PENDING);
        assertThat(outbox.getCallbackUrl()).isEqualTo("https://example.com/hooks/events");
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

        mockMvc.perform(eventPost()
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 500, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false));

    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        mockMvc.perform(eventPost()
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
    void missingApiKeyReturns401() throws Exception {
        mockMvc.perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 10, "PH")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidExpiredAndRevokedApiKeysAreRejected() throws Exception {
        mockMvc.perform(eventPost("tg_invalid")
                .content(eventJson("transaction", "payment-api", 10, "PH")))
            .andExpect(status().isUnauthorized());

        var expired = apiKeyService.create("expired", null, null,
            Instant.now().minusSeconds(60));
        mockMvc.perform(eventPost(expired.rawKey())
                .content(eventJson("transaction", "payment-api", 10, "PH")))
            .andExpect(status().isUnauthorized());

        var revoked = apiKeyService.create("revoked", null, null, null);
        apiKeyService.revoke(revoked.key().getId());
        mockMvc.perform(eventPost(revoked.rawKey())
                .content(eventJson("transaction", "payment-api", 10, "PH")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void apiKeyScopeRejectsOutOfScopeEvents() throws Exception {
        var scoped = apiKeyService.create("scoped", List.of("shipment"), List.of("warehouse"), null);

        mockMvc.perform(eventPost(scoped.rawKey())
                .content(eventJson("transaction", "payment-api", 10, "PH")))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    void invalidAggregateRuleIsRejectedBeforePersistence() throws Exception {
        mockMvc.perform(post("/api/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "invalid-aggregate",
                      "ruleType": "AGGREGATE",
                      "action": "LOG",
                      "eventType": "transaction",
                      "source": "payment-api",
                      "conditionScript": "data.amount >= 0",
                      "windowSeconds": 0,
                      "aggType": "SUM",
                      "threshold": 10,
                      "active": true
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Aggregate windowSeconds must be positive"));

        assertThat(ruleRepository.findByName("invalid-aggregate")).isEmpty();
    }

    @Test
    @WithMockUser
    void invalidRuleCannotBeActivated() throws Exception {
        Rule invalid = aggregateRule("invalid-activation", "transaction", "payment-api",
            "data.amount >= 0", AggregateType.SUM, null, 10, 0);
        invalid.setActive(false);
        ruleRepository.save(invalid);

        mockMvc.perform(patch("/api/rules/{id}/toggle", invalid.getId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Aggregate windowSeconds must be positive"));

        assertThat(ruleRepository.findById(invalid.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void accessAndRefreshTokensAreSeparatedAndLogoutRevokesRefreshSession() throws Exception {
        var login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"admin","password":"integration-admin-password"}
                    """))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode loginJson = objectMapper.readTree(login.getResponse().getContentAsString());
        String accessToken = loginJson.get("accessToken").asText();
        String refreshToken = loginJson.get("refreshToken").asText();

        mockMvc.perform(get("/api/rules")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/rules")
                .header("Authorization", "Bearer " + refreshToken))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + accessToken + "\"}"))
            .andExpect(status().isUnauthorized());

        var rotated = mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String rotatedRefresh = objectMapper.readTree(rotated.getResponse().getContentAsString())
            .get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rotatedRefresh + "\"}"))
            .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rotatedRefresh + "\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void idempotencyReplaysAndConflictsWithoutDuplicatingEvents() throws Exception {
        String idempotencyKey = "integration-idempotency-key";
        String firstPayload = eventJson("transaction", "payment-api", 700, "PH");
        String changedPayload = eventJson("transaction", "payment-api", 701, "PH");

        mockMvc.perform(eventPost().header("Idempotency-Key", idempotencyKey)
                .content(firstPayload))
            .andExpect(status().isOk());
        mockMvc.perform(eventPost().header("Idempotency-Key", idempotencyKey)
                .content(firstPayload))
            .andExpect(status().isOk());
        mockMvc.perform(eventPost().header("Idempotency-Key", idempotencyKey)
                .content(changedPayload))
            .andExpect(status().isConflict());

        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentIdempotentIngestionCreatesOneEvent() throws Exception {
        String idempotencyKey = "concurrent-idempotency-key";
        String payload = eventJson("transaction", "payment-api", 900, "PH");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> mockMvc.perform(
                    eventPost().header("Idempotency-Key", idempotencyKey).content(payload))
                    .andReturn().getResponse().getStatus()));
            }

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(statuses).contains(200);
            assertThat(statuses).allMatch(value -> value == 200 || value == 409);
            assertThat(eventRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void webhookLeaseRecoveryRejectsStaleFinalizationAndSchedulesRetry() throws Exception {
        Rule rule = new Rule();
        rule.setName("lease-rule");
        rule.setRuleType(RuleType.CONDITION);
        rule.setAction(RuleAction.WEBHOOK);
        rule.setCallbackUrl("https://example.com/hooks/leases");
        rule.setEventType("transaction");
        rule.setSource("payment-api");
        rule.setConditionScript("data.amount >= 1000");
        rule.setActive(true);
        ruleRepository.save(rule);

        mockMvc.perform(eventPost().content(eventJson("transaction", "payment-api", 1500, "PH")))
            .andExpect(status().isOk());

        Instant firstClaimAt = Instant.now();
        List<WebhookDeliveryAttempt> firstClaims = webhookOutboxDeliveryService
            .claimBatch(firstClaimAt, 10, 60_000);
        assertThat(firstClaims).hasSize(1);
        WebhookDeliveryAttempt first = firstClaims.getFirst();

        assertThat(webhookOutboxDeliveryService.claimBatch(firstClaimAt, 10, 60_000)).isEmpty();

        Instant recoveryAt = firstClaimAt.plusSeconds(61);
        List<WebhookDeliveryAttempt> recoveredClaims = webhookOutboxDeliveryService
            .claimBatch(recoveryAt, 10, 60_000);
        assertThat(recoveredClaims).hasSize(1);
        WebhookDeliveryAttempt recovered = recoveredClaims.getFirst();
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());

        WebhookDeliveryResult failure = WebhookDeliveryResult.failure(true, 503,
            "receiver unavailable", 5);
        assertThat(webhookOutboxDeliveryService.markDelivered(first,
            WebhookDeliveryResult.success(200, 5), recoveryAt)).isFalse();
        assertThat(webhookOutboxDeliveryService.markFailed(recovered, failure, recoveryAt,
            3, 100, 1000)).isTrue();
        WebhookOutbox outbox = webhookOutboxRepository.findById(recovered.outboxId()).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(WebhookOutboxStatus.RETRY_SCHEDULED);
        assertThat(outbox.getNextAttemptAt()).isAfter(recoveryAt);
    }

    @Test
    void inactiveRuleDoesNotMatch() throws Exception {
        conditionRule("inactive-rule", "transaction", "payment-api",
            "data.amount >= 1", false);

        mockMvc.perform(eventPost()
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson("transaction", "payment-api", 9999, "PH")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(false))
            .andExpect(jsonPath("$.rules").isEmpty());

        assertThat(ruleRepository.findAll()).hasSize(1);
    }
}
