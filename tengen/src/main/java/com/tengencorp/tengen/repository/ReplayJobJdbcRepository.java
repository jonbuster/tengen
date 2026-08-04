package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.dto.ReplayAggregateResult;
import com.tengencorp.tengen.dto.RuleSnapshot;
import com.tengencorp.tengen.entity.EventTimeStatus;
import com.tengencorp.tengen.entity.ReplayJobStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Types;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Direct, bounded SQL operations used by replay creation and workers. */
@Repository
public class ReplayJobJdbcRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReplayJobJdbcRepository(JdbcTemplate jdbcTemplate,
                                   NamedParameterJdbcTemplate namedJdbcTemplate,
                                   ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long countEligibleOutputEvents(Instant occurredFrom, Instant occurredTo,
                                          Long apiKeyId, RuleSnapshot snapshot) {
        String sql = """
            select count(*)
            from events event
            where event.occurred_at >= :occurredFrom
              and event.occurred_at < :occurredTo
              and (event.event_time_status is null
                   or event.event_time_status in ('ON_TIME', 'LATE_ACCEPTED'))
              and event.type = :eventType
              and event.source = :source
              and (event.api_key_id = :apiKeyId or :apiKeyId is null)
            """;
        return namedJdbcTemplate.queryForObject(sql, eventParameters(
            occurredFrom, occurredTo, apiKeyId, snapshot), Long.class);
    }

    public int materialize(ReplayMaterialization materialization) {
        String sql = """
            insert into replay_job_events (
                job_id, position, original_event_id, type, source, occurred_at,
                api_key_id, original_event_time_status, data, in_requested_range
            )
            select :jobId,
                   row_number() over (order by selected.occurred_at, selected.id),
                   selected.id,
                   selected.type,
                   selected.source,
                   selected.occurred_at,
                   selected.api_key_id,
                   selected.event_time_status,
                   selected.data,
                   selected.in_requested_range
            from (
                select event.id, event.type, event.source, event.occurred_at,
                       event.api_key_id, event.event_time_status, event.data,
                       false as in_requested_range
                from events event
                where event.occurred_at >= :warmupFrom
                  and event.occurred_at < :occurredFrom
                  and :warmupFrom < :occurredFrom
                  and (event.event_time_status is null
                       or event.event_time_status in ('ON_TIME', 'LATE_ACCEPTED'))
                  and event.type = :eventType
                  and event.source = :source
                  and (event.api_key_id = :apiKeyId or :apiKeyId is null)
                union all
                select event.id, event.type, event.source, event.occurred_at,
                       event.api_key_id, event.event_time_status, event.data,
                       true as in_requested_range
                from events event
                where event.occurred_at >= :occurredFrom
                  and event.occurred_at < :occurredTo
                  and (event.event_time_status is null
                       or event.event_time_status in ('ON_TIME', 'LATE_ACCEPTED'))
                  and event.type = :eventType
                  and event.source = :source
                  and (event.api_key_id = :apiKeyId or :apiKeyId is null)
            ) selected
            order by selected.occurred_at, selected.id
            """;
        return namedJdbcTemplate.update(sql, eventParameters(
            materialization.warmupFrom(), materialization.occurredFrom(),
            materialization.occurredTo(), materialization.apiKeyId(), materialization.snapshot())
            .addValue("jobId", materialization.jobId()));
    }

    @Transactional
    public Optional<ReplayJobLease> claimOldest(long leaseDurationMs) {
        String token = UUID.randomUUID().toString();
        List<ClaimCandidate> candidates = jdbcTemplate.query("""
            select id, status, attempt_count
              from replay_jobs
             where status = 'QUEUED'
                or (status = 'RUNNING'
                    and (lease_expires_at is null or lease_expires_at <= now()))
             order by created_at, id
             for update skip locked
             limit 1
            """, (resultSet, rowNumber) -> new ClaimCandidate(
                resultSet.getLong("id"),
                ReplayJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count")));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        ClaimCandidate candidate = candidates.get(0);
        List<ReplayJobLease> claimed = jdbcTemplate.query("""
            update replay_jobs
               set status = 'RUNNING',
                   lease_token = ?,
                   lease_expires_at = now() + (? * interval '1 millisecond'),
                   started_at = coalesce(started_at, now()),
                   attempt_count = attempt_count + 1,
                   updated_at = now(),
                   version = version + 1
             where id = ? and status = ?
            returning id, lease_token, attempt_count
            """, (resultSet, rowNumber) -> new ReplayJobLease(
                resultSet.getLong("id"),
                resultSet.getString("lease_token"),
                resultSet.getInt("attempt_count"),
                candidate.status() == ReplayJobStatus.RUNNING),
            token, leaseDurationMs, candidate.id(), candidate.status().name());
        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        ReplayJobLease lease = claimed.get(0);
        if (candidate.status() == ReplayJobStatus.RUNNING) {
            insertTransition(candidate.id(), ReplayJobStatus.RUNNING, ReplayJobStatus.RUNNING,
                "LEASE_RECOVERED", lease.attemptCount(), "LEASE_EXPIRED");
        }
        insertTransition(candidate.id(), candidate.status(), ReplayJobStatus.RUNNING,
            "CLAIMED", lease.attemptCount(), null);
        return Optional.of(lease);
    }

    public Optional<ReplayWorkerJob> findWorkerJob(Long jobId, String leaseToken) {
        String sql = """
            select id, status, rule_revision, snapshot_schema_version, rule_snapshot,
                   occurred_from, occurred_to, warmup_from, action_mode,
                   total_output_events, total_materialized_events,
                   processed_output_events, matched_events, error_events,
                   last_committed_position
              from replay_jobs
             where id = ?
               and status in ('RUNNING', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED')
               and lease_token = ?
            """;
        List<ReplayWorkerJob> jobs = jdbcTemplate.query(sql, (resultSet, rowNumber) ->
            new ReplayWorkerJob(
                resultSet.getLong("id"),
                ReplayJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("rule_revision"),
                resultSet.getInt("snapshot_schema_version"),
                objectMapper.readValue(resultSet.getString("rule_snapshot"), Map.class),
                resultSet.getTimestamp("occurred_from").toInstant(),
                resultSet.getTimestamp("occurred_to").toInstant(),
                resultSet.getTimestamp("warmup_from").toInstant(),
                resultSet.getString("action_mode"),
                resultSet.getLong("total_output_events"),
                resultSet.getLong("total_materialized_events"),
                resultSet.getLong("processed_output_events"),
                resultSet.getLong("matched_events"),
                resultSet.getLong("error_events"),
                (Long) resultSet.getObject("last_committed_position")),
            jobId, leaseToken);
        return jobs.stream().findFirst();
    }

    public List<ReplayInput> findInputs(Long jobId, long afterPosition, int batchSize) {
        String sql = """
            select job_id, position, original_event_id, type, source, occurred_at,
                   api_key_id, original_event_time_status, data, in_requested_range
              from replay_job_events
             where job_id = ? and position > ?
             order by position
             limit ?
            """;
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new ReplayInput(
                resultSet.getLong("job_id"),
                resultSet.getLong("position"),
                (Long) resultSet.getObject("original_event_id"),
                resultSet.getString("type"),
                resultSet.getString("source"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                (Long) resultSet.getObject("api_key_id"),
                parseStatus(resultSet.getString("original_event_time_status")),
                objectMapper.readValue(resultSet.getString("data"), Map.class),
                resultSet.getBoolean("in_requested_range")),
            jobId, afterPosition, batchSize);
    }

    public boolean insertRuleEvent(Long jobId, long inputPosition, Instant occurredAt,
                                   String groupKey, Double value) {
        String sql = """
            insert into replay_job_rule_events (job_id, input_position, occurred_at, group_key, value)
            values (?, ?, ?, ?, ?)
            on conflict (job_id, input_position) do nothing
            """;
        return jdbcTemplate.update(sql, jobId, inputPosition, timestamp(occurredAt), groupKey, value) == 1;
    }

    public double aggregate(Long jobId, String function, Instant since, Instant until,
                            long inputPosition, String groupKey) {
        String groupClause = "((group_key is null and cast(:groupKey as varchar) is null) "
            + "or group_key = cast(:groupKey as varchar))";
        String timeClause = "occurred_at > :since and "
            + "(occurred_at < :until or (occurred_at = :until and input_position <= :inputPosition))";
        String sql = switch (function) {
            case "COUNT" -> "select count(*) from replay_job_rule_events where job_id = :jobId and "
                + groupClause + " and " + timeClause;
            case "SUM" -> "select coalesce(sum(value), 0.0) from replay_job_rule_events where job_id = :jobId and "
                + groupClause + " and " + timeClause;
            case "AVG" -> "select coalesce(avg(value), 0.0) from replay_job_rule_events where job_id = :jobId and "
                + groupClause + " and " + timeClause;
            case "MIN" -> "select coalesce(min(value), 0.0) from replay_job_rule_events where job_id = :jobId and "
                + groupClause + " and " + timeClause;
            case "MAX" -> "select coalesce(max(value), 0.0) from replay_job_rule_events where job_id = :jobId and "
                + groupClause + " and " + timeClause;
            default -> throw new IllegalArgumentException("Unsupported replay aggregate function");
        };
        Number value = namedJdbcTemplate.queryForObject(sql, new MapSqlParameterSource()
            .addValue("jobId", jobId)
            .addValue("groupKey", groupKey)
            .addValue("since", timestamp(since), Types.TIMESTAMP)
            .addValue("until", timestamp(until), Types.TIMESTAMP)
            .addValue("inputPosition", inputPosition), Number.class);
        return value != null ? value.doubleValue() : 0.0;
    }

    public long countAggregateValues(Long jobId, Instant since, Instant until,
                                     long inputPosition, String groupKey) {
        String sql = """
            select count(value)
              from replay_job_rule_events
             where job_id = :jobId
               and ((group_key is null and cast(:groupKey as varchar) is null)
                    or group_key = cast(:groupKey as varchar))
               and occurred_at > :since
               and (occurred_at < :until
                    or (occurred_at = :until and input_position <= :inputPosition))
            """;
        Long value = namedJdbcTemplate.queryForObject(sql, new MapSqlParameterSource()
            .addValue("jobId", jobId)
            .addValue("groupKey", groupKey)
            .addValue("since", timestamp(since), Types.TIMESTAMP)
            .addValue("until", timestamp(until), Types.TIMESTAMP)
            .addValue("inputPosition", inputPosition), Long.class);
        return value != null ? value : 0;
    }

    public int insertOutcome(ReplayOutcomeInsert outcome) {
        String aggregateResult = outcome.aggregateResult() == null
            ? null : objectMapper.writeValueAsString(outcome.aggregateResult());
        String sql = """
            insert into replay_job_outcomes (
                job_id, input_position, original_event_id, type, source, occurred_at,
                matched, group_key, aggregate_result, error_category, completed_at
            ) values (
                :jobId, :inputPosition, :originalEventId, :type, :source, :occurredAt,
                :matched, :groupKey, cast(:aggregateResult as jsonb), :errorCategory, :completedAt
            )
            on conflict (job_id, input_position) do nothing
            """;
        return namedJdbcTemplate.update(sql, new MapSqlParameterSource()
            .addValue("jobId", outcome.jobId())
            .addValue("inputPosition", outcome.inputPosition())
            .addValue("originalEventId", outcome.originalEventId())
            .addValue("type", outcome.type())
            .addValue("source", outcome.source())
            .addValue("occurredAt", timestamp(outcome.occurredAt()), Types.TIMESTAMP)
            .addValue("matched", outcome.matched())
            .addValue("groupKey", outcome.groupKey())
            .addValue("aggregateResult", aggregateResult, Types.VARCHAR)
            .addValue("errorCategory", outcome.errorCategory())
            .addValue("completedAt", timestamp(outcome.completedAt()), Types.TIMESTAMP));
    }

    @Transactional
    public ReplayProgressUpdate updateProgress(Long jobId, String leaseToken, long lastPosition,
                                               long processedOutputEvents, long matchedEvents,
                                               long errorEvents, boolean completed,
                                               long leaseDurationMs) {
        String sql = """
            update replay_jobs
               set status = case when :completed and status = 'RUNNING'
                                 then 'COMPLETED' else status end,
                   processed_output_events = :processedOutputEvents,
                   matched_events = :matchedEvents,
                   error_events = :errorEvents,
                   last_committed_position = :lastPosition,
                   lease_expires_at = case when :completed and status = 'RUNNING' then null
                       else now() + (:leaseDurationMs * interval '1 millisecond') end,
                   completed_at = case when :completed and status = 'RUNNING'
                                      then now() else completed_at end,
                   updated_at = now(),
                   version = version + 1
             where id = :jobId
               and status in ('RUNNING', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED')
               and lease_token = :leaseToken
            returning status
            """;
        List<ReplayProgressUpdate> updates = namedJdbcTemplate.query(sql, new MapSqlParameterSource()
            .addValue("jobId", jobId)
            .addValue("leaseToken", leaseToken)
            .addValue("lastPosition", lastPosition)
            .addValue("processedOutputEvents", processedOutputEvents)
            .addValue("matchedEvents", matchedEvents)
            .addValue("errorEvents", errorEvents)
            .addValue("completed", completed)
            .addValue("leaseDurationMs", leaseDurationMs), (resultSet, rowNumber) ->
                new ReplayProgressUpdate(ReplayJobStatus.valueOf(resultSet.getString("status"))));
        if (updates.isEmpty()) {
            throw new IllegalStateException("Replay job lease is no longer owned");
        }
        ReplayProgressUpdate progress = updates.get(0);
        if (progress.status() == ReplayJobStatus.COMPLETED) {
            Integer attemptCount = jdbcTemplate.queryForObject(
                "select attempt_count from replay_jobs where id = ?", Integer.class, jobId);
            insertTransition(jobId, ReplayJobStatus.RUNNING, ReplayJobStatus.COMPLETED,
                "COMPLETED", attemptCount != null ? attemptCount : 0, null);
        }
        return progress;
    }

    @Transactional
    public boolean markFailed(Long jobId, String leaseToken, String category, String message) {
        int updated = jdbcTemplate.update("""
            update replay_jobs
               set status = 'FAILED',
                   failure_category = ?,
                   failure_message = ?,
                   retryable = true,
                   completed_at = now(),
                   updated_at = now(),
                   lease_token = null,
                   lease_expires_at = null,
                   version = version + 1
             where id = ? and status = 'RUNNING' and lease_token = ?
            """, category, message, jobId, leaseToken);
        if (updated != 1) {
            return false;
        }
        Integer attemptCount = jdbcTemplate.queryForObject(
            "select attempt_count from replay_jobs where id = ?", Integer.class, jobId);
        insertTransition(jobId, ReplayJobStatus.RUNNING, ReplayJobStatus.FAILED,
            "FAILED", attemptCount != null ? attemptCount : 0, category);
        return true;
    }

    @Transactional
    public boolean applyRequestedControl(Long jobId, String leaseToken) {
        List<RequestedControl> controls = jdbcTemplate.query("""
            select status, attempt_count
              from replay_jobs
             where id = ?
               and lease_token = ?
               and status in ('PAUSE_REQUESTED', 'CANCEL_REQUESTED')
             for update
            """, (resultSet, rowNumber) -> new RequestedControl(
                ReplayJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count")), jobId, leaseToken);
        if (controls.isEmpty()) {
            return false;
        }
        RequestedControl control = controls.get(0);
        ReplayJobStatus next = control.status() == ReplayJobStatus.PAUSE_REQUESTED
            ? ReplayJobStatus.PAUSED : ReplayJobStatus.CANCELLED;
        jdbcTemplate.update("""
            update replay_jobs
               set status = ?,
                   paused_at = case when ? = 'PAUSED' then now() else paused_at end,
                   cancelled_at = case when ? = 'CANCELLED' then now() else cancelled_at end,
                   completed_at = case when ? = 'CANCELLED' then now() else completed_at end,
                   retryable = case when ? = 'CANCELLED' then false else retryable end,
                   lease_token = null,
                   lease_expires_at = null,
                   updated_at = now(),
                   version = version + 1
             where id = ? and lease_token = ? and status = ?
            """, next.name(), next.name(), next.name(), next.name(), next.name(),
            jobId, leaseToken, control.status().name());
        insertTransition(jobId, control.status(), next,
            next == ReplayJobStatus.PAUSED ? "PAUSED" : "CANCELLED",
            control.attemptCount(), null);
        return true;
    }

    @Transactional
    public int recoverExpiredRequestedControls() {
        List<ExpiredControl> controls = jdbcTemplate.query("""
            select id, status, attempt_count
              from replay_jobs
             where status in ('PAUSE_REQUESTED', 'CANCEL_REQUESTED')
               and lease_expires_at is not null
               and lease_expires_at <= now()
             order by id
             for update skip locked
            """, (resultSet, rowNumber) -> new ExpiredControl(
                resultSet.getLong("id"),
                ReplayJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count")));
        for (ExpiredControl control : controls) {
            ReplayJobStatus next = control.status() == ReplayJobStatus.PAUSE_REQUESTED
                ? ReplayJobStatus.PAUSED : ReplayJobStatus.CANCELLED;
            jdbcTemplate.update("""
                update replay_jobs
                   set status = ?,
                       paused_at = case when ? = 'PAUSED' then now() else paused_at end,
                       cancelled_at = case when ? = 'CANCELLED' then now() else cancelled_at end,
                       completed_at = case when ? = 'CANCELLED' then now() else completed_at end,
                       retryable = case when ? = 'CANCELLED' then false else retryable end,
                       lease_token = null,
                       lease_expires_at = null,
                       updated_at = now(),
                       version = version + 1
                 where id = ? and status = ?
                """, next.name(), next.name(), next.name(), next.name(), next.name(),
                control.id(), control.status().name());
            insertTransition(control.id(), control.status(), next,
                next == ReplayJobStatus.PAUSED ? "PAUSED" : "CANCELLED",
                control.attemptCount(), "LEASE_RECOVERED");
        }
        return controls.size();
    }

    public long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from replay_jobs where status = ?", Long.class, status);
        return count != null ? count : 0;
    }

    public long oldestQueuedAgeSeconds() {
        Long age = jdbcTemplate.queryForObject("""
            select coalesce(extract(epoch from (now() - min(created_at)))::bigint, 0)
              from replay_jobs
             where status = 'QUEUED'
            """, Long.class);
        return age != null ? Math.max(0, age) : 0;
    }

    private MapSqlParameterSource eventParameters(Instant from, Instant to, Long apiKeyId,
                                                  RuleSnapshot snapshot) {
        return eventParameters(from, from, to, apiKeyId, snapshot);
    }

    private MapSqlParameterSource eventParameters(Instant warmupFrom, Instant occurredFrom,
                                                  Instant occurredTo, Long apiKeyId,
                                                  RuleSnapshot snapshot) {
        return new MapSqlParameterSource()
            .addValue("warmupFrom", timestamp(warmupFrom), Types.TIMESTAMP)
            .addValue("occurredFrom", timestamp(occurredFrom), Types.TIMESTAMP)
            .addValue("occurredTo", timestamp(occurredTo), Types.TIMESTAMP)
            .addValue("apiKeyId", apiKeyId, Types.BIGINT)
            .addValue("eventType", snapshot.eventType())
            .addValue("source", snapshot.source());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private EventTimeStatus parseStatus(String value) {
        return value == null ? null : EventTimeStatus.valueOf(value);
    }

    private void insertTransition(Long jobId, ReplayJobStatus fromStatus,
                                  ReplayJobStatus toStatus, String action,
                                  int attemptCount, String reason) {
        jdbcTemplate.update("""
            insert into replay_job_transitions (
                job_id, transition_sequence, from_status, to_status, action,
                actor, attempt_count, reason, transitioned_at
            )
            select ?, coalesce(max(transition_sequence), 0) + 1, ?, ?, ?,
                   'system', ?, ?, now()
              from replay_job_transitions
             where job_id = ?
            """, jobId, fromStatus != null ? fromStatus.name() : null, toStatus.name(),
            action, attemptCount, reason, jobId);
    }

    public record ReplayMaterialization(Long jobId, Instant warmupFrom, Instant occurredFrom,
                                        Instant occurredTo, Long apiKeyId, RuleSnapshot snapshot) {
    }

    public record ReplayJobLease(Long jobId, String leaseToken, int attemptCount,
                                 boolean recovered) {
    }

    public record ReplayWorkerJob(Long id, ReplayJobStatus status, int ruleRevision,
                                  int snapshotSchemaVersion,
                                  Map<String, Object> ruleSnapshot, Instant occurredFrom,
                                  Instant occurredTo, Instant warmupFrom, String actionMode,
                                  long totalOutputEvents, long totalMaterializedEvents,
                                  long processedOutputEvents, long matchedEvents,
                                  long errorEvents, Long lastCommittedPosition) {
    }

    public record ReplayProgressUpdate(ReplayJobStatus status) {
    }

    private record ClaimCandidate(Long id, ReplayJobStatus status, int attemptCount) {
    }

    private record RequestedControl(ReplayJobStatus status, int attemptCount) {
    }

    private record ExpiredControl(Long id, ReplayJobStatus status, int attemptCount) {
    }

    public record ReplayInput(Long jobId, long position, Long originalEventId, String type,
                              String source, Instant occurredAt, Long apiKeyId,
                              EventTimeStatus eventTimeStatus, Map<String, Object> data,
                              boolean inRequestedRange) {
    }

    public record ReplayOutcomeInsert(Long jobId, long inputPosition, Long originalEventId,
                                      String type, String source, Instant occurredAt,
                                      boolean matched, String groupKey,
                                      ReplayAggregateResult aggregateResult,
                                      String errorCategory, Instant completedAt) {
    }
}
