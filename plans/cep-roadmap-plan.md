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
| Trigger lifecycle and alert deduplication | Later feature |
| Transactional outbox and async actions | Later feature |

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

## Later Assessment Roadmap

1. Trigger modes such as `EDGE`, cooldown, and once-per-window.
2. Event idempotency keys.
3. Transactional outbox with asynchronous webhook delivery and retries.
4. Rule validation and lifecycle/versioning.
5. Sequence and absence patterns.
6. Watermarks, allowed lateness, and late-event handling.
7. Broker connectors and replay/backfill.
