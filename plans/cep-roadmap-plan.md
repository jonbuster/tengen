# Tengen CEP Roadmap

This document tracks progress against the CEP assessment and defines the next practical feature to implement.

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
| Trigger lifecycle and alert deduplication | Cooldown and `EDGE` implemented; once-per-window remains later |
| Transactional outbox and async actions | Synchronous webhook delivery with retries implemented; outbox/async delivery remains later |

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
- Synchronous best-effort webhook delivery with up to three attempts and short backoff.
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

## Later Assessment Roadmap

1. Transactional outbox with asynchronous webhook delivery and retries.
2. Rule lifecycle/versioning and audit history; basic request validation and active toggling are already implemented.
3. Sequence and absence patterns.
4. Watermarks and allowed lateness; event-time windows and future-event exclusion are already implemented.
5. Broker connectors and replay/backfill.
6. Event API response controls: optionally omit aggregate details for external producers and add an explicit `X-Idempotency-Replayed` response header.
