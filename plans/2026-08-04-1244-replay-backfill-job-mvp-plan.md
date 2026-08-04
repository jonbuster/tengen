# Replay and Backfill Job MVP Plan

## Status: Implemented — 2026-08-04

## Recommendation

Add a safe historical evaluation job that reads already-persisted events and
evaluates them against one immutable rule revision. Keep all replay state and
results isolated from live processing, and prohibit webhook delivery in this
first slice.

The MVP is an analysis/backfill foundation, not a second ingestion path. It
answers questions such as "How would revision 4 of this rule have behaved over
last week's events?" without changing the original events or current runtime
state.

## Why This Shape Comes First

Directly sending old events through `EventService` is unsafe:

- live watermarks would classify many historical events as `TOO_LATE`;
- live aggregate, sequence, absence, EDGE, cooldown, and once-per-window state
  could be changed by the replay;
- existing event traces could no longer describe the original processing pass;
- webhook actions could be duplicated; and
- rule definitions may have changed since an event was first accepted.

The first replay feature therefore uses immutable inputs, an immutable rule
snapshot, job-scoped evaluation state, and `NO_ACTIONS` behavior.

## Current-State Findings

- Accepted events are stored with `(occurred_at, id)` ordering, source, type,
  payload, API-key association, event-time classification, and processing trace.
- Rule revisions already contain immutable JSON snapshots and are never removed
  by retention.
- Production aggregate state is stored in `rule_events` and is scoped by rule
  revision and group key.
- Sequence and absence processing use separate durable live-state tables.
- Event-time watermarks are global per source and event type and must not be
  advanced by a replay.
- Webhook intents, cooldown reservations, and trigger state are committed from
  the live ingestion path.
- The admin console already has JWT-protected APIs, TanStack Query, MUI data
  grids, status chips, and polling patterns that can be reused.

## Goals

- Let an authenticated admin create a replay job for one rule revision and one
  bounded event-time range.
- Materialize immutable event inputs so retention or later event changes cannot
  alter an in-flight job.
- Evaluate events deterministically in `(occurred_at, original_event_id)` order.
- Support `CONDITION` and `AGGREGATE` rule revisions in the MVP.
- Keep aggregate windows and group-key behavior consistent with production.
- Persist progress and per-event outcomes without changing live tables.
- Show basic job status, progress, summary counts, and paginated outcomes.
- Recover safely when a worker process stops after a committed batch.
- Add bounded configuration, metrics, tests, and retention compatibility.

## Non-Goals

- Do not create new rows in `events` from replay inputs.
- Do not update original event traces or outcomes.
- Do not mutate live watermarks, `rule_events`, sequence/absence instances,
  cooldown state, trigger windows, or the webhook outbox.
- Do not send webhooks, even when the selected snapshot uses `WEBHOOK`.
- Do not support `SEQUENCE` or `ABSENCE` revisions in this MVP; reject them with
  a clear validation response rather than approximating their behavior.
- Do not replay multiple rules in one job.
- Do not upload CSV/JSON files or read from external object storage.
- Do not add pause, resume, cancellation, manual retry, or searchable job
  history; those belong to the controls/history follow-up plan.
- Do not implement Kafka ingestion in this slice.

## Replay Contract

### Job request

Create a job with:

- `ruleId`;
- required `ruleRevision`;
- `occurredFrom` inclusive;
- `occurredTo` exclusive; and
- optional `apiKeyId` to restrict the source event set.

Require `occurredFrom < occurredTo`. Add configurable limits for maximum range
duration and maximum materialized events. Recommended initial defaults are 31
days and 10,000 output-range events.

Only immutable snapshots with rule type `CONDITION` or `AGGREGATE` are valid.
The current activation/archive status does not matter because the requested
revision is evaluated as historical configuration. A snapshot that cannot pass
the current validator is rejected before any job is created.

### Event selection

- Use event time, not receipt time, for the requested range.
- Process output-range events where `occurred_at >= occurredFrom` and
  `occurred_at < occurredTo`.
- Exclude events originally classified as `TOO_LATE` because they were outside
  the accepted event-time contract. Include `ON_TIME`, `LATE_ACCEPTED`, and
  legacy rows with a null status.
- Apply the optional API-key filter before materialization.
- Materialize only routes relevant to the selected snapshot.
- For aggregate rules, also materialize warm-up events after
  `occurredFrom - windowSeconds` and before `occurredFrom`. Warm-up rows build
  job-local window state but do not produce visible outcomes or summary counts.
- Use original event ID as the deterministic tie-breaker for equal timestamps.

### Side-effect policy

`actionMode` is fixed internally to `NO_ACTIONS` and is returned in job details.
It is not caller-selectable in this slice. A match records what the rule would
have matched, but never reports a webhook as queued or delivered.

### Result meaning

Each output-range event receives one replay outcome containing:

- original event ID and immutable route/timestamp summary;
- matched or not matched;
- resolved group key when applicable;
- aggregate type, value, threshold, and window for aggregate rules;
- a bounded evaluation error category when applicable; and
- processing position and completion timestamp.

Expression failures follow current production semantics and become non-matches,
but the replay outcome records `EXPRESSION_ERROR` so an admin can distinguish
them from an ordinary false condition. Event payloads and expressions must not
be copied into logs or error messages.

## Data Model

Use the next available Flyway migration, currently expected to be
`V10__replay_jobs.sql` when this plan is implemented first.

### `replay_jobs`

Persist:

- identity and status: `id`, `status` (`QUEUED`, `RUNNING`, `COMPLETED`,
  `FAILED`), and optimistic `version`;
- rule identity: `rule_id`, `rule_revision`, snapshot schema version, and an
  immutable `rule_snapshot` JSONB value;
- selection: requested start/end, optional API-key ID, warm-up start, and fixed
  `action_mode = NO_ACTIONS`;
- progress: total output events, total materialized events, processed output
  events, matched events, error events, and last committed position;
- worker ownership: lease token and lease expiry;
- audit timing: created by, created, started, updated, completed; and
- a bounded failure category/message that never includes an event body,
  expression, API key, or callback URL.

Index status plus creation time for worker claims and direct job lookup. Keep
rule revision history protected by the existing rule lifecycle policy.

### `replay_job_events`

Materialize a stable input stream with:

- job ID and monotonically increasing position;
- original event ID as an informational value without a foreign key that would
  block normal event retention;
- type, source, occurred time, API-key ID, original event-time status, and data
  JSONB copied from the event;
- `in_requested_range` to distinguish warm-up rows from visible output rows; and
- a unique `(job_id, position)` constraint.

Copying the input payload is intentional: a running job remains deterministic
if the original event is removed by retention. Enforce the materialization cap
before inserting rows.

### `replay_job_rule_events`

Store job-scoped aggregate state:

- job ID, input position, occurred time, group key, and optional numeric value;
- one row for each event whose aggregate pre-filter and condition pass; and
- indexes matching production window access by
  `(job_id, group_key, occurred_at, input_position)`.

This table must never reference or update live `rule_events`.

### `replay_job_outcomes`

Store one row per output-range input position with matched state, group key,
aggregate result JSONB, optional fixed error category, and creation time. Enforce
one outcome per `(job_id, input_position)`.

Use cascading deletes from a replay job to its materialized inputs, job-local
state, and outcomes. The original events and rule revisions are unaffected.

## Backend Design

### Creation service

Add a JWT-protected service that:

1. locks/loads the requested immutable rule revision;
2. deserializes and validates its snapshot;
3. rejects unsupported rule types and invalid ranges;
4. counts eligible output events and rejects an empty or over-limit selection;
5. writes the job and materialized input rows in one transaction; and
6. returns the queued job without evaluating it on the HTTP request thread.

Use a direct bounded `INSERT ... SELECT` or equivalent batch insert so the
application does not retain all event payloads in memory during materialization.

### Isolated evaluator

Add a replay-specific evaluator rather than calling production
`RuleEngine.evaluate`, which persists live state.

- Extract or reuse pure helpers for route checks, Aviator evaluation, field-path
  normalization, numeric extraction, grouping, and threshold comparison.
- For `CONDITION`, evaluate the snapshot without writing state.
- For `AGGREGATE`, insert the matching candidate into
  `replay_job_rule_events`, then calculate the window with the same exclusive
  lower and inclusive upper boundaries as production.
- Scope all aggregate queries by job and group key.
- Preserve production handling for missing/overlong group keys and non-numeric
  values.
- Do not instantiate managed `Rule` entities or attach the historical snapshot
  to the live persistence context.

Add parity tests around the shared pure helpers so replay and live evaluation do
not drift silently.

### Worker

Add an optional scheduled replay worker, disabled or enabled explicitly through
`tengen.replay.worker.*` configuration.

- Claim the oldest queued or lease-expired running job using PostgreSQL row
  locking and a UUID lease.
- Process a configurable input batch in one transaction.
- Skip already committed positions and use unique constraints for idempotence.
- Persist job-local aggregate state, outcomes, counters, and last position in
  the same batch transaction.
- Renew the lease between batches.
- Mark the job `COMPLETED` only after all materialized rows are committed.
- Mark unexpected deterministic failures `FAILED` with safe bounded context.
- Let a stopped worker's lease expire so another instance can continue from the
  last committed position.

Do not perform outbound HTTP work from this worker.

## Admin API

Add JWT-protected endpoints:

```text
POST /api/replay-jobs
GET  /api/replay-jobs/{id}
GET  /api/replay-jobs/{id}/outcomes?page=0&size=25&matched=true
```

The create endpoint returns `202 Accepted`. Detail responses include status,
selection, immutable rule identity/name/type, action mode, counts, percentage,
timestamps, and safe failure information. Outcome pages use the existing page
shape and a maximum size of 100.

Add `/api/replay-jobs/**` to JWT-protected routes. Do not expose these APIs to
producer API keys.

## Frontend

Add a `Replays` navigation entry and a `/replays` page that can:

- select a rule and immutable revision;
- enter the inclusive start and exclusive end timestamps;
- optionally select an API-key filter;
- explain that the MVP is analysis-only and never sends webhooks;
- create a job and show the returned job ID;
- poll only the selected active job; and
- show progress, summary counts, safe errors, and a paginated outcome table.

Disable unsupported sequence/absence revisions in the form with an explanation.
Use the existing timezone preference for display while sending UTC ISO values.
Do not add pause/cancel/retry controls or a searchable all-jobs table yet.

## Configuration and Metrics

Add bounded settings such as:

- `TENGEN_REPLAY_WORKER_ENABLED`;
- poll interval, initial delay, batch size, and lease duration;
- maximum requested range days; and
- maximum materialized output events.

Register counters for created, completed, failed, matched, and evaluation-error
results, plus gauges for queued/running depth and oldest queued age. Avoid rule
names and job IDs as metric labels.

## Retention

Until the controls/history slice adds terminal-job cleanup, replay inputs and
results remain durable. Document their storage cost and keep the initial event
cap conservative. Do not add replay tables to the existing retention service in
this MVP because silent cleanup would make the new job detail contract unclear.

## Implementation Sequence

1. Add schema, entities/enums, repositories, and snapshot DTOs.
2. Add pure shared evaluation helpers and job-scoped aggregate queries.
3. Add creation/materialization service with validation and limits.
4. Add the leased worker and transactional batch processing.
5. Add detail/outcome APIs and security routing.
6. Add frontend types, creation form, active-job detail, and outcomes table.
7. Add metrics, configuration, documentation, and focused tests.
8. Run backend tests, then frontend lint, tests, and production build.

## Verification Plan

### Backend unit tests

- Range, revision, rule-type, and cap validation.
- Snapshot validation and action-mode enforcement.
- Route, condition, numeric value, and group-key parity with production.
- COUNT, SUM, AVG, MIN, and MAX window boundaries.
- Warm-up events affect aggregates but do not create visible outcomes.
- Expression errors are safe non-matches with a fixed outcome category.
- Batch retry does not duplicate job-local state or outcomes.

### Database and integration tests

- Inputs remain available after deleting an eligible original event through the
  normal retention path.
- Concurrent workers claim a job only once per active lease.
- Lease expiry resumes at the last committed position.
- Replay writes no live rule, sequence, absence, trigger, watermark, event
  outcome, or webhook state.
- Equal timestamps are processed by original event ID.
- API-key and event-time-status selection policies are enforced.
- Job and outcome APIs are JWT protected and paginated safely.

### Frontend tests

- Unsupported revisions cannot be submitted.
- Date range and limit errors render clearly.
- Job creation uses the selected revision and filters.
- Polling stops in terminal states.
- Progress and aggregate outcomes render correctly.

## Acceptance Criteria

The slice is complete when an admin can select a `CONDITION` or `AGGREGATE`
revision and a bounded historical event-time range, start an asynchronous job,
observe deterministic progress and results, restart a worker without duplicate
outcomes, and verify that no original event, live rule state, watermark,
webhook, or delivery record changed.

## Risks and Mitigations

- **Replay changes production behavior.** Use dedicated tables and an evaluator
  that has no references to live runtime repositories or the outbox.
- **Historical results drift from production semantics.** Share pure helpers and
  add parity tests for field paths, expressions, grouping, thresholds, and
  window boundaries.
- **Materialized payloads consume excessive storage.** Enforce range/event caps,
  expose the selected count before confirmation, and add retention in the
  controls/history slice.
- **Retention makes a job non-repeatable.** Copy immutable event inputs at job
  creation rather than reading live event rows throughout execution.
- **A worker crash duplicates results.** Commit state, outcome, and checkpoint
  atomically and enforce unique job/position keys.
- **Users mistake analysis for live reprocessing.** Label the mode `NO_ACTIONS`
  in the API and UI and never expose an action toggle in this slice.

## Ordered Follow-Ups

1. Implement the [Kafka connector MVP](2026-08-04-1244-kafka-connector-mvp-plan.md).
2. Implement [replay job controls and history](2026-08-04-1244-replay-job-controls-history-plan.md).
3. Plan sequence and absence replay only after their job-scoped state machines
   and finite-range closure semantics are specified.
4. Consider a separately approved apply-mode backfill only after deduplication,
   trigger, webhook, and watermark reset policies are explicit.
