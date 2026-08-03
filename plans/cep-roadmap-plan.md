# Tengen CEP Roadmap

This document tracks progress against the CEP assessment and records the next practical feature to implement.

## Completed: Aggregate Correctness Slice

Implemented the first correctness-focused slice without changing the public API shape:

- Aggregate field paths now support both `data.amount` and `amount`.
- Newly created and updated rules store aggregate fields in canonical form.
- Existing rules using the `data.` prefix remain compatible.
- Aggregate windows are bounded by event time:
  - `occurred_at > windowStart`
  - `occurred_at <= currentEventTime`
- The rule tester includes the candidate event in aggregate calculations without persisting it.
- The tester explains that candidate events are temporary.
- The rule form documents both accepted aggregate-field formats.
- Regression coverage was added for field paths, event-time ordering, aggregate behavior, and non-persistent testing.

### Assessment items addressed

| Assessment concern | Status |
|---|---|
| Non-COUNT aggregate fields may not resolve `data.amount` | Implemented |
| Late events can include future events in windows | Implemented |
| Rule testing omits the candidate event from aggregates | Implemented |
| Keyed/grouped aggregates | Implemented |
| Trigger lifecycle and alert deduplication | Cooldown, `EDGE`, and once-per-window implemented |
| Transactional outbox and async actions | Durable outbox, background worker, and delivery history implemented |

## Implemented: Keyed Aggregates

Implemented with optional grouping. A blank `groupBy` preserves global aggregation; a configured field creates an independent aggregate window per resolved key.

### Goal

Allow aggregate rules to calculate separate windows for a business key such as user, account, device, card, or email instead of combining all matching events globally.

### Example

Rule configuration:

```json
{
  "eventType": "transaction",
  "source": "payment-api",
  "conditionScript": "data.amount >= 0",
  "aggType": "SUM",
  "aggField": "data.amount",
  "groupBy": "data.userId",
  "windowSeconds": 300,
  "threshold": 1000
}
```

Events from different users must maintain independent aggregate state:

```text
Alice: 700 + 400 = 1100 -> match
Bob:   900             -> no match
```

### Initial scope

- Support one grouping field per rule.
- Accept dotted paths such as `data.userId` and `userId`.
- Require a non-null group value for keyed aggregate evaluation.
- Keep existing global aggregate behavior when no `groupBy` is configured.
- Support the existing aggregate types: `COUNT`, `SUM`, `AVG`, `MIN`, and `MAX`.
- Return the matched group key in aggregate match responses.
- Do not add multiple grouping fields, joins, sequences, or repartitioned processing yet.

### Backend changes

1. Add an optional `groupBy` field to `Rule`, `RuleRequest`, `RuleResponse`, and the frontend `Rule`/`RuleRequest` types.
2. Add a nullable `group_key` column to `rule_events` and an index covering `(rule_id, group_key, occurred_at)`.
3. Resolve the configured group path using the same normalization behavior as aggregate fields.
4. Persist the resolved group key with each matching `RuleEvent`.
5. Add group-key parameters to all aggregate repository queries.
6. Use the current event's group key when calculating production aggregates.
7. Use the candidate event's group key when calculating tester aggregates.
8. Preserve global aggregation when `groupBy` is blank or null.
9. Include `groupKey` in the aggregate result returned for a match.

### Frontend changes

- Add an optional `Group by field` input to the aggregate section of the rule form.
- Provide guidance such as `data.userId` or `userId`.
- Display the group key in the rule tester result and event match response where available.
- Leave the field blank for the existing global aggregation behavior.

### Behavioral rules

- Events with the same rule and same resolved group key share a window.
- Events with different group keys never contribute to one another's aggregate.
- A missing group key does not enter a keyed aggregate window and does not trigger the rule.
- Window boundaries remain exclusive at the lower bound and inclusive at the current event time.
- Tester evaluations include the candidate event only in memory and never create a `rule_events` row.

### Test plan

- A global rule without `groupBy` continues to aggregate all matching events.
- A keyed SUM rule keeps Alice and Bob totals separate.
- `data.userId` and `userId` resolve to the same group key.
- A missing group key does not match a keyed rule.
- COUNT, SUM, AVG, MIN, and MAX respect the group key.
- Late events do not include future events from the same group.
- The tester includes the candidate in the correct group without persistence.
- The API response includes the matched group key.
- Existing condition rules and ungrouped aggregate rules remain compatible.

### Acceptance criteria

The feature is complete when an admin can create a rule grouped by `data.userId`, ingest events for at least two users, and observe that only the user whose own aggregate crosses the threshold is matched. Existing rules without `groupBy` must continue behaving as they do today.

## Implemented: CEP Baseline and Supporting Features

The original event processor and frontend migration plans also have these implemented capabilities:

- JSON event ingestion through `POST /api/events` with persisted events.
- CONDITION rules and windowed AGGREGATE rules using `COUNT`, `SUM`, `AVG`, `MIN`, and `MAX`.
- Durable webhook outbox persistence for matched webhook delivery intents.
- Optional API-key-scoped event idempotency keys with request conflict detection and response replay.
- REST rule administration, rule testing, JWT-protected admin access, and the Next.js/MUI admin UI.
- API-key generation, hashed storage, revocation, and event-to-key association through `X-API-Key`.

## Implemented: Webhook Cooldown

Implemented the first trigger-lifecycle slice for webhook actions:

- Rules accept an optional non-negative `cooldownSeconds` value.
- Cooldown state is durable in `rule_action_state` and scoped by rule plus aggregate group key.
- Matched rules remain matched when webhook delivery is suppressed.
- Successful deliveries start or refresh cooldown; failed deliveries leave it available for retry.
- Event responses include additive `suppressedRules` details.
- Rule testing does not deliver webhooks or mutate cooldown state.

## Implemented: EDGE Trigger Mode

Implemented a durable rising-edge trigger mode for webhook actions:

- Rules accept `EVERY_MATCH`, `EDGE`, or `ONCE_PER_WINDOW` trigger modes.
- `EDGE` sends on false-to-true transitions and ignores consecutive matches.
- A failed EDGE delivery remains retryable on the next matching event.
- EDGE state is scoped by rule and aggregate group key.
- The rule form exposes trigger mode and cooldown under a collapsed Advanced section.

## Implemented: Once-Per-Window Trigger Mode

Implemented durable event-time trigger deduplication for aggregate webhook rules:

- `ONCE_PER_WINDOW` is valid for webhook aggregate rules with a positive `windowSeconds` value.
- Delivery state is scoped by rule, aggregate group key, and fixed epoch-aligned event-time window.
- A successful delivery suppresses later matches in the same window while keeping the rule logically matched.
- Failed delivery leaves the window available for retry.
- Window records are durable and protect against duplicate delivery when late events revisit an older window.
- The aggregate value remains the existing rolling event-time aggregate; fixed buckets apply only to trigger deduplication.

## Implemented: Durable Webhook Outbox

Implemented on 2026-08-02 as the first asynchronous delivery slice:

- Added a durable `webhook_outbox` table/entity in the existing PostgreSQL database.
- Event persistence, rule evaluation, trigger reservations, and eligible webhook intents commit in one transaction.
- Removed outbound HTTP calls from the event-ingestion path.
- Added immutable callback URL, rule-name, payload, scope, trigger, and deduplication snapshots.
- Added `PENDING` delivery state with attempt and scheduling fields consumed by the delivery worker.
- Added unique delivery keys for `EVERY_MATCH`, `EDGE`, and `ONCE_PER_WINDOW` actions.
- Added pending reservations for cooldown-scoped and once-per-window actions to prevent duplicate queued work.
- Added additive `queuedRules` information to event responses.
- Preserved side-effect-free rule testing and idempotent event response replay.

The outbox persists delivery intent; the implemented background worker now processes those rows asynchronously.

## Implemented: Background Webhook Delivery Worker

Implemented on 2026-08-02 as the second asynchronous delivery slice:

- A scheduled worker claims due `PENDING` and `RETRY_SCHEDULED` rows in batches.
- PostgreSQL `FOR UPDATE SKIP LOCKED` and UUID leases prevent concurrent duplicate claims.
- Expired leases are automatically claimable after a worker restart or crash.
- Each worker pass performs one HTTP attempt outside the database transaction.
- `2xx` responses are marked `DELIVERED`.
- Timeouts, connection errors, `408`, `429`, and `5xx` responses are retried with exponential backoff and jitter.
- Permanent `4xx` responses and exhausted retries become `DEAD_LETTER` rows.
- Successful delivery updates cooldown and once-per-window state only when the matching pending reservation is still current.
- Worker settings are configurable through application properties and Docker Compose environment variables.

Webhook delivery is now asynchronous, automatic, and observable through the delivery-history API and UI.

## Implemented: Webhook Delivery History

Implemented on 2026-08-02 as the third asynchronous delivery slice:

- JWT-protected list, detail, and manual-retry endpoints operate on the existing outbox rows.
- The Next.js Deliveries page provides server-side pagination and filters for status, rule, event, date range, and destination or rule-name search.
- Delivery details show the stored payload, attempts, HTTP result, latest error, timestamps, trigger metadata, and manual-retry time.
- Manual retry requeues the same `DEAD_LETTER` row, preserving its identity and delivery history.
- Manual refresh is always available. Optional five-second refresh runs only while enabled and active rows are visible; it is off by default.
- End-to-end manual verification covered successful delivery, retry exhaustion, dead-lettering, retrying the same row, filtering, idempotent event replay, and idempotency conflict handling.

## Completed Asynchronous Delivery Sequence

The feature was split into three independently reviewable plans. All three asynchronous delivery slices are now implemented.

### 1. Durable Webhook Outbox — Implemented

Plan: [`durable-webhook-outbox-plan.md`](durable-webhook-outbox-plan.md)

- Commit webhook delivery intent with the event and trigger reservation.
- Remove outbound HTTP calls from the ingestion transaction.
- Deduplicate `EVERY_MATCH`, `EDGE`, and `ONCE_PER_WINDOW` delivery intents.
- Preserve keyed scopes, cooldown behavior, and idempotent event retries.
- Add queued-action information to the event response without claiming delivery success.

### 2. Background Webhook Delivery Worker — Implemented

Plan: [`webhook-delivery-worker-plan.md`](webhook-delivery-worker-plan.md)

- Claim work safely with database locking and leases.
- Deliver outside database transactions.
- Retry transient failures with configurable exponential backoff.
- Recover abandoned work after process failure.
- Persist success, retry, and dead-letter states.

### 3. Webhook Delivery History — Implemented

Plan: [`webhook-delivery-history-plan.md`](webhook-delivery-history-plan.md)

- Add paginated, filterable JWT-protected admin APIs.
- Add a Deliveries page to the Next.js console.
- Show status, rule/event correlation, attempts, timing, and latest errors.
- Allow controlled manual retry of dead-lettered rows without duplicating delivery intent.
- Provide a user-controlled auto-refresh toggle, off by default, and a manual refresh action for active deliveries.

## Implemented: Configurable Sequence Rules

Implemented as the next CEP pattern slice:

- Added `SEQUENCE` rules with ordered two-to-five event steps.
- Added optional shared correlation through `groupBy` and a total event-time window.
- Durable sequence instances advance the oldest eligible progress row and consume each event once per rule.
- Sequence progress is revision-scoped and active instances are cancelled when rule lifecycle changes reset runtime state.
- Completed sequence responses and webhook payloads include ordered step event details.
- The admin console supports adding, removing, reordering, and testing sequence steps without persisting test events.

The implementation intentionally leaves absence detection, watermarks, reusable events, and branching patterns for later slices.

## Implemented: Event Explorer

Implemented as the next production-usability slice:

- New events persist an immutable matched-rule trace with aggregate/sequence details and webhook action outcomes.
- Admins can search accepted events by identity, routing fields, API key, match state, trace availability, and received-time range.
- Event details show the raw payload, matched-rule snapshots, suppression reasons, and related webhook delivery state.
- Delivery history links back to its source event, and event details link to filtered delivery history.
- Events written before trace capture remain visible and are explicitly marked as trace unavailable; no historical outcomes are inferred.

## Later Assessment Roadmap

1. Rule lifecycle/versioning and audit history — implemented with revision-scoped aggregate/trigger state, immutable snapshots, archive/unarchive, restore, and stale-write protection.
2. Absence patterns — not implemented; negative conditions and absence timers remain future work.
3. Watermarks and allowed lateness — partially implemented: event-time windows and future-event exclusion are implemented, but watermark state, grace periods, and late-event correction/retraction are not.
4. Broker connectors and replay/backfill — not implemented; ingestion currently uses the HTTP event API and idempotency replay is not historical backfill.
5. Event API response controls — partially implemented: aggregate, sequence, queued, suppressed, and idempotent response data are available, but configurable aggregate omission for external producers and an explicit `X-Idempotency-Replayed` response header are not implemented.
