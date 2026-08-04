package com.tengencorp.tengen;

import com.tengencorp.tengen.dto.ReplayJobCreateRequest;
import com.tengencorp.tengen.dto.ReplayJobPage;
import com.tengencorp.tengen.dto.ReplayJobOutcomePage;
import com.tengencorp.tengen.dto.ReplayJobResponse;
import com.tengencorp.tengen.dto.ReplayJobTransitionResponse;
import com.tengencorp.tengen.dto.RuleSnapshot;
import com.tengencorp.tengen.entity.AggregateType;
import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.EventTimeStatus;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleRevision;
import com.tengencorp.tengen.entity.RuleRevisionChangeType;
import com.tengencorp.tengen.entity.RuleType;
import com.tengencorp.tengen.repository.EventRepository;
import com.tengencorp.tengen.repository.ReplayJobJdbcRepository;
import com.tengencorp.tengen.repository.RuleEventRepository;
import com.tengencorp.tengen.repository.RuleRepository;
import com.tengencorp.tengen.repository.RuleRevisionRepository;
import com.tengencorp.tengen.service.ReplayJobService;
import com.tengencorp.tengen.service.ReplayJobControlService;
import com.tengencorp.tengen.service.ReplayJobWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "tengen.webhook.worker.enabled=false",
    "tengen.absence.worker.enabled=false",
    "tengen.replay.worker.enabled=true",
    "tengen.replay.worker.poll-interval-ms=60000",
    "tengen.replay.worker.initial-delay-ms=60000",
    "tengen.replay.worker.batch-size=2",
    "tengen.retention.enabled=false",
    "admin.password=integration-admin-password",
    "jwt.secret=integration-test-secret-with-more-than-32-bytes",
    "tengen.webhook.worker.signing-secret=integration-test-signing-secret-with-more-than-32-bytes"
})
class ReplayJobIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RuleRepository ruleRepository;

    @Autowired
    private RuleRevisionRepository revisionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private RuleEventRepository ruleEventRepository;

    @Autowired
    private ReplayJobService replayJobService;

    @Autowired
    private ReplayJobWorker replayJobWorker;

    @Autowired
    private ReplayJobControlService replayJobControlService;

    @Autowired
    private ReplayJobJdbcRepository replayJobJdbcRepository;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE replay_job_outcomes, replay_job_rule_events, "
            + "replay_job_events, replay_jobs, rule_action_state, rule_action_windows, "
            + "webhook_outbox, rule_events, rule_revisions, event_idempotency, "
            + "event_stream_watermarks, events, rules, api_keys, refresh_sessions "
            + "RESTART IDENTITY CASCADE");
    }

    @Test
    void conditionReplayCompletesWithoutWritingLiveState() {
        Rule rule = conditionRule("historical payments", "data.amount >= 100");
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = from.plusSeconds(300);
        saveEvent("payment", "billing", from.plusSeconds(10), Map.of("amount", 125));
        saveEvent("payment", "billing", from.plusSeconds(20), Map.of("amount", 25));
        saveEventWithStatus("payment", "billing", from.plusSeconds(30),
            Map.of("amount", 125), EventTimeStatus.TOO_LATE);

        ReplayJobResponse created = replayJobService.create(
            new ReplayJobCreateRequest(rule.getId(), 1, from, to, null), "admin");
        assertThat(created.status()).isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.QUEUED);
        assertThat(created.totalOutputEvents()).isEqualTo(2);

        replayJobWorker.runOnce();

        ReplayJobResponse completed = replayJobService.get(created.id());
        assertThat(completed.status()).isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.COMPLETED);
        assertThat(completed.processedOutputEvents()).isEqualTo(2);
        assertThat(completed.matchedEvents()).isEqualTo(1);
        assertThat(completed.progressPercentage()).isEqualTo(100.0);
        assertThat(ruleEventRepository.count()).isZero();

        ReplayJobOutcomePage outcomes = replayJobService.outcomes(created.id(), 0, 100, null);
        assertThat(outcomes.totalElements()).isEqualTo(2);
        assertThat(outcomes.content()).extracting(outcome -> outcome.matched())
            .containsExactly(true, false);
    }

    @Test
    void aggregateWarmupBuildsOnlyJobLocalWindowState() {
        Rule rule = aggregateRule("historical sum", 100.0, 300);
        Instant from = Instant.parse("2026-08-02T00:00:00Z");
        Instant to = from.plusSeconds(60);
        saveEvent("payment", "billing", from.minusSeconds(30), Map.of("amount", 60));
        saveEvent("payment", "billing", from.plusSeconds(10), Map.of("amount", 50));

        ReplayJobResponse created = replayJobService.create(
            new ReplayJobCreateRequest(rule.getId(), 1, from, to, null), "admin");
        assertThat(created.totalOutputEvents()).isEqualTo(1);
        assertThat(created.totalMaterializedEvents()).isEqualTo(2);

        replayJobWorker.runOnce();

        ReplayJobResponse completed = replayJobService.get(created.id());
        assertThat(completed.status())
            .withFailMessage("status=%s failure=%s", completed.status(), completed.failureMessage())
            .isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.COMPLETED);
        ReplayJobOutcomePage outcomes = replayJobService.outcomes(created.id(), 0, 100, null);
        assertThat(outcomes.content()).hasSize(1);
        assertThat(outcomes.content().get(0).matched()).isTrue();
        assertThat(outcomes.content().get(0).aggregate().value()).isEqualTo(110.0);
        assertThat(ruleEventRepository.count()).isZero();
    }

    @Test
    void replayControlsPauseResumeAndRecordHistory() {
        Rule rule = conditionRule("controlled replay", "data.amount >= 100");
        Instant from = Instant.parse("2026-08-03T00:00:00Z");
        Instant to = from.plusSeconds(300);
        saveEvent("payment", "billing", from.plusSeconds(10), Map.of("amount", 125));
        saveEvent("payment", "billing", from.plusSeconds(20), Map.of("amount", 25));

        ReplayJobResponse created = replayJobService.create(
            new ReplayJobCreateRequest(rule.getId(), 1, from, to, null), "admin");
        ReplayJobResponse paused = replayJobControlService.pause(
            created.id(), etag(created), "admin");
        assertThat(paused.status()).isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.PAUSED);

        ReplayJobResponse resumed = replayJobControlService.resume(
            paused.id(), etag(paused), "admin");
        assertThat(resumed.status()).isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.QUEUED);

        ReplayJobPage page = replayJobService.list(
            0, 25, null, rule.getId(), 1, "admin", created.id(), null, null);
        assertThat(page.totalElements()).isEqualTo(1);

        replayJobWorker.runOnce();
        ReplayJobResponse completed = replayJobService.get(created.id());
        assertThat(completed.status()).isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.COMPLETED);

        List<ReplayJobTransitionResponse> transitions = replayJobService.transitions(created.id());
        assertThat(transitions).extracting(ReplayJobTransitionResponse::action)
            .contains("CREATED", "PAUSED", "RESUMED", "CLAIMED", "COMPLETED");
    }

    @Test
    void retryableReplayFailureResumesFromCheckpoint() {
        Rule rule = conditionRule("retryable replay", "data.amount >= 100");
        Instant from = Instant.parse("2026-08-03T01:00:00Z");
        Instant to = from.plusSeconds(300);
        saveEvent("payment", "billing", from.plusSeconds(10), Map.of("amount", 125));

        ReplayJobResponse created = replayJobService.create(
            new ReplayJobCreateRequest(rule.getId(), 1, from, to, null), "admin");
        ReplayJobJdbcRepository.ReplayJobLease lease = replayJobJdbcRepository
            .claimOldest(300_000)
            .orElseThrow();
        assertThat(replayJobJdbcRepository.markFailed(
            lease.jobId(), lease.leaseToken(), "WORKER_ERROR", "simulated failure")).isTrue();

        ReplayJobResponse failed = replayJobService.get(created.id());
        assertThat(failed.status()).isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.FAILED);
        assertThat(failed.retryable()).isTrue();

        ReplayJobResponse retried = replayJobControlService.retry(
            failed.id(), etag(failed), "admin");
        assertThat(retried.status()).isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.QUEUED);

        replayJobWorker.runOnce();
        assertThat(replayJobService.get(created.id()).status())
            .isEqualTo(com.tengencorp.tengen.entity.ReplayJobStatus.COMPLETED);
        assertThat(replayJobService.transitions(created.id()))
            .extracting(ReplayJobTransitionResponse::action)
            .contains("FAILED", "RETRIED");
    }

    private String etag(ReplayJobResponse job) {
        return "\"" + job.version() + "\"";
    }

    private Rule conditionRule(String name, String expression) {
        Rule rule = new Rule();
        rule.setName(name);
        rule.setRuleType(RuleType.CONDITION);
        rule.setAction(RuleAction.LOG);
        rule.setEventType("payment");
        rule.setSource("billing");
        rule.setConditionScript(expression);
        rule.setActive(false);
        return saveWithRevision(rule);
    }

    private Rule aggregateRule(String name, double threshold, int windowSeconds) {
        Rule rule = new Rule();
        rule.setName(name);
        rule.setRuleType(RuleType.AGGREGATE);
        rule.setAction(RuleAction.LOG);
        rule.setEventType("payment");
        rule.setSource("billing");
        rule.setConditionScript("data.amount >= 0");
        rule.setAggType(AggregateType.SUM);
        rule.setAggField("data.amount");
        rule.setThreshold(threshold);
        rule.setWindowSeconds(windowSeconds);
        rule.setActive(false);
        return saveWithRevision(rule);
    }

    private Rule saveWithRevision(Rule rule) {
        rule = ruleRepository.saveAndFlush(rule);
        revisionRepository.saveAndFlush(new RuleRevision(
            rule.getId(), 1, RuleRevisionChangeType.CREATED, "test",
            objectMapper.convertValue(RuleSnapshot.from(rule), Map.class), null));
        return rule;
    }

    private void saveEvent(String type, String source, Instant occurredAt, Map<String, Object> data) {
        eventRepository.saveAndFlush(new Event(type, source, occurredAt, data));
    }

    private void saveEventWithStatus(String type, String source, Instant occurredAt,
                                     Map<String, Object> data, EventTimeStatus status) {
        Event event = new Event(type, source, occurredAt, data);
        event.setEventTimeStatus(status);
        eventRepository.saveAndFlush(event);
    }
}
