# Webhook Cooldown Trigger Plan

## Status: Implemented — 2026-08-02

Completed on 2026-08-02. The backend, REST response, frontend rule form, and CEP roadmap were updated. Manual verification confirmed that the first matching event is delivered and a second matching event within the cooldown remains matched while appearing in `suppressedRules`.

The original slice preceded asynchronous delivery. In the current implementation, an eligible match reserves and queues an outbox row; cooldown begins only after the background worker successfully delivers it.

## Summary

Adds an optional cooldown to prevent repeated webhook notifications while a rule remains matched. This was the next high-impact, small-scope feature after keyed aggregates.

The rule remains logically matched, but repeated webhook delivery is suppressed until the cooldown expires.

## Key Changes

- Add optional `cooldownSeconds` to rule create/update responses, backend models, frontend types, and the rule form.
- Show cooldown configuration when `Action = WEBHOOK`.
- Treat `null` or `0` as disabled cooldown behavior.
- Apply cooldown per:
  - `rule + groupKey` for keyed rules.
  - `rule` for global rules.
- Use processing time (`Instant.now()`), not event timestamps, for cooldown timing.
- Add durable `rule_action_state` storage containing:
  - rule ID
  - scope key
  - last successful delivery timestamp
- Only successful background-worker delivery starts or refreshes cooldown.
- Failed webhook delivery leaves cooldown unchanged so later events can retry.
- Keep `matched: true` when a webhook is suppressed.
- Add `suppressedRules` to the event response so callers can see that the rule matched but its action was throttled.
- Leave `LOG` behavior unchanged because `LOG` currently only means the match is returned in the API response.
- The original slice did not change synchronous delivery; the later transactional-outbox and worker slices now provide the current asynchronous behavior.

## Behavior

Example: a keyed rule with a 600-second cooldown.

```text
Alice event 1 -> matched, webhook queued and delivered by the worker
Alice event 2 -> matched, webhook suppressed
Bob event 1   -> matched independently, webhook delivered
After 600s    -> Alice webhook can deliver again
```

A failed webhook attempt does not consume the cooldown.

Rule testing does not deliver webhooks or mutate cooldown state.

## Backend Design

- Add `cooldownSeconds` as an optional non-negative rule property.
- Add a `RuleActionState` entity/table with a unique `(rule_id, scope_key)` constraint.
- Use an empty or reserved scope key for global rules and the resolved keyed aggregate value for grouped rules.
- Ensure the action-state row exists before acquiring a database-backed lock for cooldown evaluation.
- Check the last successful delivery and pending reservation before creating another outbox row.
- Update the delivery timestamp only when the worker finalizes a successful delivery.
- Keep detection and action throttling separate: evaluation remains matched even when delivery is suppressed.

## Frontend/API Changes

- Add a numeric `Cooldown (seconds)` field to webhook rule configuration.
- Explain that cooldown controls repeated webhook delivery, not rule detection.
- Add `suppressedRules` to the event response as an additive field.
- Preserve existing response fields and behavior for rules without cooldown.

## Test Plan

- First matching webhook queues one outbox row; successful worker delivery records delivery time.
- A second match within cooldown remains matched but does not call the webhook.
- The response includes the rule in `suppressedRules`.
- A match after cooldown calls the webhook again.
- Failed delivery does not start cooldown.
- Alice's cooldown does not suppress Bob's keyed rule action.
- Global rules share one cooldown scope.
- `cooldownSeconds = 0` preserves current behavior.
- `LOG` rules remain unaffected.
- Rule tester never creates or updates action state.
- Run backend integration tests and the frontend production build.

## Assumptions

- Cooldown applies to `WEBHOOK` actions only in this fast-win version.
- Cooldown state is durable across application restarts.
- Database-backed state is protected by a unique `(rule_id, scope_key)` constraint and serialized during delivery checks.
- Existing API fields remain compatible; `cooldownSeconds` and `suppressedRules` are additive.
- Transactional outbox persistence, delivery history, dead-letter handling, and asynchronous retries were implemented in the subsequent webhook-delivery slices.
