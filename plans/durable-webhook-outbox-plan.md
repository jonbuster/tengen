# Durable Webhook Outbox Plan

## Status: Planned — implementation pending

Create a durable webhook outbox so a matched event and its webhook delivery intent are committed in the same database transaction. This is the first of three asynchronous delivery slices and must be completed before the background worker or delivery-history UI.

## Problem

`EventService` currently calls `WebhookClient` while processing `POST /api/events`. This couples event acceptance to an external HTTP service and creates two reliability risks:

- a slow callback delays the event-ingestion response;
- a webhook can succeed remotely and then be duplicated if the local database transaction rolls back or the process stops before commit.

Logging a failed request is not enough because the delivery intent is lost when the process exits.

## Goal

Persist one immutable webhook delivery intent whenever an eligible webhook rule matches. The event, rule evaluation state, trigger reservation, and outbox row must either commit together or roll back together. No outbound HTTP request should occur inside the event-ingestion transaction.

## Scope

- Add durable outbox storage in PostgreSQL.
- Enqueue webhook work from `EventService` instead of calling `WebhookClient` directly.
- Preserve `EVERY_MATCH`, `EDGE`, `ONCE_PER_WINDOW`, keyed scope, and cooldown eligibility.
- Prevent duplicate outbox rows for the same logical trigger.
- Keep rule testing side-effect free.
- Add an additive `queuedRules` field to the event response.
- Leave actual delivery, retries, and the admin history UI to the next two plans.

## Out of Scope

- Calling webhook endpoints.
- Retry scheduling and dead-letter handling.
- Admin delivery-history endpoints or pages.
- Kafka, RabbitMQ, or another external broker.
- Exactly-once processing by the receiving webhook server.

## Data Model

Add a `webhook_outbox` table represented by a `WebhookOutbox` entity.

Suggested fields:

- `id` — generated primary key.
- `event_id` — event that produced the delivery intent.
- `rule_id` — originating rule when it still exists.
- `rule_name` — immutable snapshot for diagnostics after rule edits or deletion.
- `callback_url` — immutable destination snapshot.
- `payload` — JSONB body to deliver; never reconstruct it from current rule state.
- `scope_key` — normalized aggregate group key or the global sentinel.
- `trigger_mode` — trigger mode snapshot.
- `window_start` — fixed bucket start for `ONCE_PER_WINDOW`, otherwise null.
- `deduplication_key` — stable unique identifier for the logical delivery.
- `status` — initially `PENDING`.
- `attempt_count` — initially `0`.
- `next_attempt_at` — initially the creation time.
- `last_attempt_at`, `delivered_at`, and `last_error` — initially null.
- `created_at` and `updated_at`.

Indexes and constraints:

- Unique constraint on `deduplication_key`.
- Index on `(status, next_attempt_at, id)` for the future worker.
- Index on `(rule_id, created_at)` for history filtering.
- Index on `(event_id)` for event correlation.
- Store enough immutable rule information that a rule can be edited or deleted without invalidating queued work.

## Deduplication Keys

Create the key from database-owned identifiers, not from the mutable payload:

| Trigger mode | Logical key |
|---|---|
| `EVERY_MATCH` | rule ID + event ID |
| `EDGE` | rule ID + scope key + durable edge transition identifier |
| `ONCE_PER_WINDOW` | rule ID + scope key + fixed window start |

The database unique constraint is the final concurrency guard. Treat a duplicate insert as an already-queued action rather than an ingestion failure.

## Trigger and Cooldown Semantics

Asynchronous delivery introduces a period where a webhook is eligible but not yet delivered. Add a durable reservation so events arriving during that period do not enqueue duplicate work.

- `EVERY_MATCH`: every distinct accepted event may enqueue one delivery.
- `EDGE`: reserve the rising edge when the outbox row is created. Consecutive matching events must not create more rows. A later non-match resets the edge only according to the existing rule scope.
- `ONCE_PER_WINDOW`: the outbox unique key reserves the rule, scope, and window immediately. Late events in the same window reuse that reservation.
- Cooldown: a pending delivery reserves the scope while it is in flight. The cooldown timestamp still begins only after successful delivery by the worker.
- Terminal failure must remain attached to the original delivery so an admin retry does not create a second logical action.

If the current action-state tables cannot express a pending reservation safely, add a nullable outbox reference or an explicit pending flag rather than overloading `lastDeliveredAt`.

## Backend Changes

1. Add `WebhookOutboxStatus` with the initial `PENDING` value and the later worker states documented in the worker plan.
2. Add the `WebhookOutbox` entity and repository.
3. Extract webhook payload construction from `EventService` into a dedicated factory so queued payloads retain the current contract.
4. Add a `WebhookOutboxService.enqueue(...)` method that creates the deduplication key and persists the row.
5. Replace direct `WebhookClient.deliver(...)` calls in event processing with eligibility evaluation plus enqueue.
6. Update action-state/window reservation atomically with the outbox insert.
7. Add `queuedRules` to `EventResponse`; keep `matched` and `rules` unchanged.
8. Keep `suppressedRules` for matches blocked by cooldown or trigger policy. A newly queued rule is not suppressed.
9. Ensure `RuleEngine.test(...)` and `/api/rules/test` never create outbox or reservation rows.
10. Leave `WebhookClient` available for the worker, but remove it from the ingestion path.

## Transaction Boundaries

The event-processing transaction should perform only local database work:

```text
authenticate API key
  -> persist event
  -> evaluate rules
  -> reserve eligible trigger
  -> persist webhook outbox row
  -> persist idempotency response
  -> commit
```

The worker must not observe an outbox row before its event transaction commits. If any local step fails, the event, rule-event rows, trigger reservation, and outbox rows roll back together.

## API Compatibility

The ingestion response remains an acceptance response, not a delivery receipt. Add only:

```json
{
  "queuedRules": ["High value transaction"]
}
```

Do not claim the webhook was delivered in the synchronous response. Existing clients that ignore unknown fields remain compatible.

## Test Plan

### Focused coverage

- A matched `LOG` rule creates no outbox row.
- A matched webhook rule creates exactly one `PENDING` row with the expected immutable payload.
- The event and outbox row commit together.
- A processing failure rolls back both the event and outbox row.
- Event ingestion does not call `WebhookClient`.
- An idempotent event retry does not create another outbox row.
- `EVERY_MATCH` creates one row per distinct event.
- Concurrent attempts for one logical `EDGE` or window trigger create one row.
- Keyed rules create independent reservations for different group keys.
- Cooldown-suppressed matches do not enqueue.
- Rule testing creates no outbox or action-state rows.
- Event responses distinguish queued and suppressed rules.

Spring Boot integration tests must not be created or run until the user approves them, per the repository instructions.

## Acceptance Criteria

This slice is complete when event ingestion commits an eligible webhook delivery intent durably without making an outbound HTTP request, returns without waiting on the callback endpoint, and cannot create duplicate intents for the same logical trigger.

## Rollout Notes

- Deploy the outbox and worker together in production, or keep the worker feature flag disabled only for a deliberately short migration window.
- Existing synchronous retry code should not run alongside outbox delivery for the same event.
- Old trigger state must remain compatible; introduce nullable reservation fields with safe defaults.

## Next Plan

Implement [webhook-delivery-worker-plan.md](webhook-delivery-worker-plan.md) after the outbox write path is stable.
