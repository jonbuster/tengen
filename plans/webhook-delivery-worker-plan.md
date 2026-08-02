# Background Webhook Delivery Worker Plan

## Status: Implemented — 2026-08-02

Process committed webhook outbox rows outside the event-ingestion transaction. This second asynchronous delivery slice is implemented and depends on the data model and enqueue behavior in `durable-webhook-outbox-plan.md`.

## Goal

Deliver queued webhooks reliably with bounded retries, exponential backoff, safe concurrent claiming, restart recovery, and dead-letter handling. Event ingestion must remain independent of callback latency and availability.

## Scope

- Poll eligible outbox rows on a configurable schedule.
- Claim work safely across multiple application instances.
- Perform HTTP delivery outside database transactions.
- Record success, retry, and terminal failure state.
- Recover work abandoned by a stopped worker.
- Update cooldown and trigger delivery state only after success.
- Expose configuration and operational metrics through logs.

## Out of Scope

- Admin browsing and manual retry UI.
- A separate queueing product or message broker.
- Unlimited retries.
- Guaranteeing exactly-once effects at the webhook receiver.
- Webhook signing and secret rotation, which can be a separate security feature.

## State Machine

Use these outbox states:

```text
PENDING -> PROCESSING -> DELIVERED
                    \-> RETRY_SCHEDULED -> PROCESSING
                    \-> DEAD_LETTER
```

- `PENDING`: committed and ready for its first attempt.
- `PROCESSING`: leased by one worker.
- `RETRY_SCHEDULED`: failed transiently and waits for `nextAttemptAt`.
- `DELIVERED`: received a successful HTTP response; terminal.
- `DEAD_LETTER`: retries exhausted or failure is non-retryable; terminal until manually retried.

## Safe Work Claiming

Use a short database transaction to claim a batch:

1. Select eligible `PENDING` or `RETRY_SCHEDULED` rows ordered by `next_attempt_at, id`.
2. Lock them with PostgreSQL `FOR UPDATE SKIP LOCKED`.
3. Mark each row `PROCESSING` and set a unique lease token plus `lease_expires_at`.
4. Commit the claim transaction.
5. Perform HTTP calls outside the transaction.
6. Finalize each result in a new short transaction, validating the lease token.

This permits multiple app instances without double-claiming work and avoids holding database locks during network calls.

## Lease Recovery

A process can stop after claiming a row. Before claiming new work, or in a separate scheduled recovery pass:

- find `PROCESSING` rows with an expired lease;
- move them to `RETRY_SCHEDULED`;
- clear the old lease token;
- set `nextAttemptAt` to the current time;
- retain `attemptCount` and diagnostic information.

Do not depend only on in-memory executor state.

## Delivery Behavior

- Deliver the immutable `callbackUrl` and JSON payload stored in the outbox row.
- Treat any HTTP `2xx` response as success.
- Treat timeouts, connection failures, `408`, `429`, and `5xx` responses as retryable.
- Treat other `4xx` responses as terminal by default because the request is unlikely to succeed unchanged.
- Bound stored error text and avoid logging payloads or credentials.
- Capture the HTTP status when available.
- Respect interruption during shutdown and leave unfinished leases recoverable.

`WebhookClient` performs one attempt per call. Retry policy belongs in the worker so attempt state is durable.

## Retry Policy

Use configurable exponential backoff with jitter:

```text
delay = min(maxDelay, baseDelay * 2^(attemptCount - 1)) + jitter
```

Suggested defaults:

- poll interval: 1 second;
- batch size: 25;
- maximum attempts: 8;
- base delay: 5 seconds;
- maximum delay: 15 minutes;
- lease duration: 5 minutes;
- connect timeout: 3 seconds;
- read timeout: 5 seconds.

Expose values through `application.properties` and Docker Compose environment variables.

## Success Semantics

On successful delivery, one finalization transaction must:

- set the outbox row to `DELIVERED`;
- increment `attemptCount` and set `lastAttemptAt` and `deliveredAt`;
- clear lease fields and error details;
- record the successful delivery timestamp in `RuleActionState` when cooldown requires it;
- mark the corresponding `RuleActionWindow` delivered for `ONCE_PER_WINDOW`;
- clear any pending reservation that still points to this outbox row. Do not
  reassert an old `EDGE` match if a later non-match has already reset that state.

Use the outbox row's stored scope and trigger metadata rather than re-evaluating the current rule definition.

## Failure Semantics

On failed delivery:

- increment `attemptCount` and save the bounded error/status details;
- schedule the next attempt when the error is retryable and attempts remain;
- otherwise set `DEAD_LETTER`;
- never create a new outbox row for a retry;
- do not start or refresh cooldown;
- retain trigger reservation so repeated events do not create duplicate logical deliveries.

Manual retry in the delivery-history feature will requeue the same row with an explicit audit timestamp.

## Backend Components

Suggested responsibilities:

- `WebhookDeliveryWorker` — scheduling and batch orchestration.
- `WebhookOutboxClaimRepository` — native claim and lease-recovery operations.
- `WebhookDeliveryService` — one HTTP attempt and result classification.
- `WebhookOutboxFinalizer` — transactional success/failure updates.
- `WebhookDeliveryProperties` — validated configuration values.

Enable scheduling explicitly and keep each component small enough to test without starting the whole application.

## Observability

Add structured logs for:

- outbox ID, rule ID/name, attempt number, and result;
- claim batch size and duration;
- delivery latency;
- retry scheduling and dead-letter transitions;
- expired lease recovery.

Never log API keys, authorization cookies, full event payloads, or URL user-info/query secrets.

Useful counters for a future metrics endpoint:

- deliveries attempted, succeeded, retried, and dead-lettered;
- number of pending/overdue rows;
- age of the oldest pending delivery;
- delivery latency.

## Test Plan

### Unit and repository coverage

- Successful `2xx` delivery transitions to `DELIVERED`.
- Retryable failures schedule the correct next-attempt time.
- Permanent `4xx` failures transition to `DEAD_LETTER`.
- Exhausting the attempt limit transitions to `DEAD_LETTER`.
- Exponential backoff respects the configured maximum.
- Two workers cannot claim the same row.
- An expired lease is recovered after a simulated process stop.
- HTTP calls occur without an open claim transaction or database row lock.
- Successful delivery records cooldown success, clears the matching `EDGE`
  reservation without reasserting stale state, and marks the window delivered.
- Failed delivery does not start cooldown or mark a window delivered.
- A keyed delivery updates only its own group scope.
- Application restart retains and resumes pending work.
- Worker shutdown leaves claimed work recoverable.

Spring Boot integration tests must not be created or run until the user approves them, per the repository instructions.

## Acceptance Criteria

This slice is complete when queued webhooks are delivered after the event transaction commits, transient failures survive restarts and retry automatically, exhausted or permanent failures become durable dead-letter records, and concurrent application instances cannot deliver the same attempt simultaneously.

## Implemented Components

Implemented on 2026-08-02:

- `WebhookDeliveryWorker` scheduled polling with configurable interval and batch size.
- PostgreSQL `FOR UPDATE SKIP LOCKED` claims with UUID leases and stale-lease recovery.
- Single-attempt `WebhookClient` with timeout and HTTP status classification.
- Exponential retry backoff with jitter and maximum-attempt dead-lettering.
- Transactional success/failure finalization with cooldown and once-per-window state updates.
- Conditional worker enablement through `WEBHOOK_WORKER_ENABLED` for staged rollout.

## Operational Rollout

1. Deploy with the worker disabled while verifying the outbox schema if a staged rollout is needed.
2. Enable one worker instance with a small batch size.
3. Verify pending rows drain and state transitions are correct.
4. Enable remaining instances after confirming safe concurrent claiming.
5. Alert operationally on growing dead-letter count or oldest-pending age.

## Follow-up Plan

Operational follow-ups are documented in [webhook-delivery-history-plan.md](webhook-delivery-history-plan.md) and include searchable history, diagnostics, and controlled manual retry.
