# Replay Job Controls and History Plan

## Status: Planned - 2026-08-04 12:44 PHT

## Recommendation

Extend the replay/backfill MVP with safe pause, resume, cancellation, and manual
retry controls plus a searchable operational history and immutable transition
audit.

Keep controls cooperative and batch-boundary based. Never interrupt a database
transaction in the middle of evaluating an event; a running worker observes a
requested control after its current bounded batch commits.

## Prerequisite

Implement this plan only after the
[replay/backfill job MVP](2026-08-04-1244-replay-backfill-job-mvp-plan.md) has:

- immutable rule and event snapshots;
- isolated job-local evaluation state;
- leased worker claims;
- atomic outcome/state/checkpoint batches; and
- basic job detail and outcome APIs.

The [RabbitMQ connector with admin UI MVP](2026-08-04-1515-rabbitmq-connector-ui-mvp-plan.md)
may be implemented before this slice but is not controlled by replay-job endpoints.

## Goals

- List replay jobs with server-side pagination and useful filters.
- Inspect configuration, progress, results, worker ownership timing, failure
  state, and a complete transition audit.
- Pause queued or running jobs without losing committed progress.
- Resume paused jobs from the last committed checkpoint.
- Cancel jobs safely and permanently.
- Manually retry eligible failed jobs from their last committed checkpoint.
- Reject stale or invalid control requests deterministically.
- Make active-job monitoring optional and efficient in the admin console.
- Add retention for terminal replay jobs and their copied payloads.
- Add operational metrics and safe audit logging.

## Non-Goals

- Do not add an action-enabled replay mode or webhook delivery.
- Do not edit a job's rule revision, event range, filters, or immutable inputs.
- Do not restart a completed or cancelled job; create a new job instead.
- Do not manually delete individual jobs or outcomes.
- Do not change Kafka consumer offsets, pause Kafka partitions, or browse Kafka
  dead-letter messages.
- Do not schedule recurring replay jobs.
- Do not add multi-user roles; all authenticated admins retain the same access
  model as the current console.
- Do not export payloads or results to CSV/object storage in this slice.

## State Machine

Extend replay status values to:

```text
QUEUED -> RUNNING -> COMPLETED
   |         |
   |         +-------> FAILED -> QUEUED (manual retry)
   |         |
   |         +-------> PAUSE_REQUESTED -> PAUSED -> QUEUED (resume)
   |         |
   |         +-------> CANCEL_REQUESTED -> CANCELLED
   |
   +-------> PAUSED
   +-------> CANCELLED
```

Also allow:

- `PAUSED -> CANCELLED`;
- `FAILED -> CANCELLED`; and
- `PAUSE_REQUESTED -> CANCEL_REQUESTED`.

Terminal states are `COMPLETED` and `CANCELLED`. `FAILED` is inactive but
retryable when its fixed failure category is marked retryable.

### Pause

- Pausing `QUEUED` moves directly to `PAUSED` because no worker owns it.
- Pausing `RUNNING` writes `PAUSE_REQUESTED` under a row lock.
- The worker finishes its current bounded batch, verifies the lease token,
  changes the job to `PAUSED`, clears the lease, and stops.
- Repeating pause on `PAUSED` is idempotent.
- Pause on a terminal or failed job returns `409 Conflict`.

### Resume

- Resume is valid only from `PAUSED`.
- It changes the job to `QUEUED`, clears stale lease fields, and preserves all
  inputs, outcomes, job-local aggregate rows, counters, and checkpoint.
- The worker starts with the first position after the last committed checkpoint.
- Reconstructing in-memory helper state, if any, must use committed job tables;
  never depend on the previous process remaining alive.

### Cancel

- Cancelling `QUEUED`, `PAUSED`, or `FAILED` moves directly to `CANCELLED`.
- Cancelling `RUNNING` or `PAUSE_REQUESTED` writes `CANCEL_REQUESTED`.
- The worker finishes its current bounded batch, changes the job to
  `CANCELLED`, clears the lease, and stops.
- Cancellation keeps committed inputs, outcomes, state, counters, and progress
  visible until retention removes the terminal job.
- Repeating cancellation on `CANCELLED` is idempotent.
- Cancelling `COMPLETED` returns `409 Conflict` because the result is already
  final.

### Manual retry

- Retry is valid only from `FAILED` when `retryable=true`.
- It changes the job to `QUEUED`, increments `attempt_count`, clears the active
  failure fields, and preserves the prior failure in transition history.
- It resumes after the last committed checkpoint; the failed batch must have
  rolled back completely.
- Unique job/position constraints remain the last defense against duplicate
  outcomes.
- Deterministic configuration/validation failures are not retryable. The admin
  must create a new corrected job.

## Concurrency and Stale-Write Contract

- Add or use an optimistic job `version` exposed as an `ETag`.
- Require `If-Match` on pause, resume, cancel, and retry requests.
- Lock the job row before validating and applying a transition.
- Return `409 Conflict` for a state that no longer permits the requested action.
- Return the current representation and ETag from successful mutations.
- Worker finalization must compare its lease token so an expired worker cannot
  overwrite a newer pause, cancellation, retry, or completion.
- Keep worker batch transactions short enough that a control request waits only
  for a bounded batch, not an entire job.

If a control request races with normal completion, the first committed valid
transition wins. A later request receives the current terminal state rather than
rewriting it.

## Data Model

Use the next available Flyway migration. If the preceding plans are implemented
in order, this is expected to be `V12__replay_job_controls.sql`.

### Extend `replay_jobs`

Add or confirm:

- expanded status constraint;
- optimistic `version`;
- `attempt_count`;
- fixed `failure_category`, bounded safe `failure_message`, and `retryable`;
- requested-control actor/time fields if useful for worker handoff;
- paused, cancelled, and last-retried timestamps; and
- indexes for status, created time, rule/revision, creator, and terminal time.

Do not store free-form pause/cancel reasons in the MVP. Fixed action names and
authenticated actor identity are sufficient and avoid storing unbounded input.

### `replay_job_transitions`

Add an append-only transition table with:

- job ID;
- monotonic transition sequence;
- nullable prior status and required new status;
- action (`CREATED`, `CLAIMED`, `PAUSE_REQUESTED`, `PAUSED`, `RESUMED`,
  `CANCEL_REQUESTED`, `CANCELLED`, `FAILED`, `RETRIED`, `COMPLETED`,
  `LEASE_RECOVERED`);
- actor (`system` for worker transitions or the authenticated admin username);
- attempt number;
- fixed reason/failure category when applicable; and
- transition timestamp.

Enforce uniqueness on `(job_id, sequence)` and index `(job_id, transitioned_at)`.
Cascade transition deletion only when retention deletes the parent job.

Record the job's initial `QUEUED` transition in the same transaction that
creates/materializes it.

## Backend Services

### Transition service

Centralize all state changes in one service so controllers and workers cannot
invent transitions independently.

The service must:

- lock and load the job;
- validate `If-Match` and allowed source/target state;
- apply timestamps, attempt/failure fields, and lease clearing consistently;
- append the immutable transition row in the same transaction; and
- return a fresh detail DTO and version.

Worker claim, lease recovery, completion, and failure also go through this
service or a shared transition helper.

### Worker changes

- Claim only `QUEUED` jobs or lease-expired `RUNNING` jobs.
- On lease recovery, append `LEASE_RECOVERED` before continuing.
- After every committed batch, reload/lock the job and check status plus lease.
- Honor `PAUSE_REQUESTED` and `CANCEL_REQUESTED` before claiming another batch.
- Never mark a control-requested job `COMPLETED` unless all inputs were already
  committed before the control transition; resolve that race under the same row
  lock and record the winning transition.
- Classify failures with fixed categories and mark whether manual retry is safe.
- Release the lease on paused, cancelled, completed, and failed states.

## Admin API

Add or extend JWT-protected endpoints:

```text
GET  /api/replay-jobs?page=0&size=25&status=FAILED&ruleId=12&createdBy=admin&from=...&to=...
GET  /api/replay-jobs/{id}
GET  /api/replay-jobs/{id}/transitions
GET  /api/replay-jobs/{id}/outcomes?page=0&size=25&matched=true
POST /api/replay-jobs/{id}/pause
POST /api/replay-jobs/{id}/resume
POST /api/replay-jobs/{id}/cancel
POST /api/replay-jobs/{id}/retry
```

List filters:

- status;
- rule ID;
- exact rule revision;
- creator;
- created-time range; and
- job ID lookup.

Use server-side pagination with a maximum size of 100 and newest-first ordering.
Return safe summary fields only; copied event payloads remain limited to outcome
detail views already authorized for admins.

Control responses return the updated detail and ETag. Use structured `400` for
invalid inputs, `404` for missing jobs, `409` for invalid/racing states, and
`412 Precondition Failed` or the project's established stale-write response for
an `If-Match` mismatch. Choose one stale-write convention and test it across all
four actions.

## Frontend

Upgrade `/replays` into an operations page consistent with Deliveries:

- server-side paginated grid;
- filters for status, rule, revision, creator, job ID, and creation range;
- columns for status, rule revision, requested range, progress, match/error
  counts, creator, attempt, created time, and completed/paused time;
- detail drawer/dialog containing immutable request settings, safe failure
  context, worker/lease timing, results link, and transition timeline;
- state-aware Pause, Resume, Cancel, and Retry buttons;
- confirmation dialogs for cancellation and retry;
- visible explanation that cancellation preserves partial results and replay
  never sends webhooks; and
- manual refresh plus optional auto-refresh, off by default.

Auto-refresh every five seconds only when enabled and the current page contains
active statuses. Stop polling when no visible row is active. Preserve filter and
pagination state in the URL using the existing Deliveries/Event Explorer
patterns.

Send the current ETag with every control mutation. On a stale response, refresh
the selected job and explain that its state changed.

## Audit Logging

Log successful admin controls after their transaction commits with:

- fixed event name;
- actor;
- job ID;
- prior and new status;
- attempt; and
- request correlation ID when the logging plan is implemented.

Do not log rule snapshots, event payloads, expressions, API-key identifiers,
callback URLs, or copied outcome data. The transition table is the authoritative
job audit; logs provide cross-request diagnostics.

## Metrics

Add:

- gauges by bounded status category for queued, running, pause-requested,
  paused, cancel-requested, and failed jobs;
- oldest queued age;
- counters for pause, resume, cancel, retry, completion, failure, and lease
  recovery; and
- job duration and processed-event count summaries without job/rule IDs as
  metric labels.

Avoid creator, rule name, rule ID, and failure messages as labels.

## Retention

Replay jobs copy event payloads, so terminal history must be bounded.

- Reuse the global retention age by default rather than introducing an unrelated
  policy.
- Delete only terminal `COMPLETED` and `CANCELLED` jobs older than the cutoff.
- Keep `FAILED` jobs until they are retried/cancelled or a separately configured
  failed-job maximum age is explicitly approved; otherwise an actionable failure
  could disappear silently.
- Delete parent jobs in bounded batches and let foreign-key cascades remove
  transitions, outcomes, job-local state, and materialized inputs.
- Never remove active, paused, pause-requested, or cancel-requested jobs.
- Count deleted replay rows in retention metrics and safe summary logs.

Document that creating a new job is required after retained history is deleted.

## Implementation Sequence

1. Extend statuses/job fields and add the immutable transition table.
2. Centralize transition validation, optimistic concurrency, and audit writes.
3. Update the worker to honor requested controls and record system transitions.
4. Add list, transition, and control APIs with ETags.
5. Build the paginated history UI, detail timeline, confirmations, and polling.
6. Add audit logs, metrics, and terminal-job retention.
7. Add focused backend/frontend tests and update README/roadmap status only after
   verification.
8. Run backend tests, then frontend lint, tests, production build, and the admin
   smoke flow.

## Verification Plan

### State and service tests

- Every allowed transition succeeds and appends one audit row.
- Every disallowed transition returns conflict without changing the job.
- Repeated pause/cancel requests follow the documented idempotency behavior.
- `If-Match` rejects stale actions.
- Retry preserves committed progress and increments attempt count.
- Non-retryable failures reject retry.
- Transition actor, sequence, attempt, and timestamp are correct.

### Worker and database integration tests

- A running job pauses after its current batch and releases its lease.
- Resuming continues at the next position without duplicate state/outcomes.
- Cancelling a running job preserves committed partial results and processes no
  later batch.
- A failed batch rolls back fully; retry starts at the prior checkpoint.
- An expired lease is recovered once and cannot be finalized by the stale
  worker.
- Completion racing with pause/cancel produces one valid terminal/control state
  and a consistent transition history.
- Two admins racing controls cannot create an impossible state sequence.
- Retention removes only eligible terminal jobs in bounded batches.

### API tests

- Pagination limits and all filters are enforced.
- Detail, outcomes, and transitions require JWT authentication.
- Control endpoints require matching ETags and return updated ETags.
- Errors use structured, non-sensitive responses.

### Frontend tests

- Filters and page state are reflected in the URL.
- Buttons appear only for valid states.
- Pause, resume, cancel, and retry send the current ETag.
- Stale/conflict responses refresh and explain the new state.
- Auto-refresh runs only when enabled and active rows are visible.
- Transition history, partial progress, failures, and attempts render correctly.

## Manual Verification

1. Create a replay large enough to span several worker batches.
2. Pause it while running and verify progress stops after one bounded batch.
3. Restart the application, resume the job, and verify it continues without
   duplicate outcomes.
4. Create another job, cancel it while running, and verify partial results remain
   visible but no later input is processed.
5. Induce a retryable failure, restore the dependency, retry, and verify the
   attempt/transition history.
6. Race two browser actions and verify stale-write protection.
7. Confirm no webhook, watermark, or live rule-state rows changed throughout.

## Acceptance Criteria

The feature is complete when an authenticated admin can find any retained replay
job, understand its immutable request and complete transition history, pause and
resume active work without duplicate results, cancel work while preserving
committed progress, retry a safe failure from its checkpoint, and observe that
concurrent/stale controls cannot corrupt state. Terminal copied inputs and
results must be removed only by the documented bounded retention policy.

## Risks and Mitigations

- **Pause/cancel interrupts a partial transaction.** Honor controls only after a
  bounded batch commits.
- **A stale worker overwrites a control.** Require row locking and lease-token
  comparison for worker transitions.
- **Resume duplicates aggregate state or outcomes.** Use the committed position
  checkpoint and unique job/position constraints.
- **Retry hides the original failure.** Store immutable failure transitions and
  increment attempts instead of overwriting history.
- **Two admins create an invalid transition sequence.** Require ETags and validate
  under a row lock.
- **Copied payload history grows without bound.** Apply global retention to
  completed/cancelled jobs in bounded parent batches.
- **Polling overloads the API.** Keep auto-refresh opt-in and active-row aware.
