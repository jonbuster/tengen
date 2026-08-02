# Webhook Cooldown Trigger Plan

## Status: Implemented

Completed on 2026-08-02. The backend, REST response, frontend rule form, and CEP roadmap were updated. Manual verification confirmed that the first matching event is delivered and a second matching event within the cooldown remains matched while appearing in `suppressedRules`.

## Summary

Add an optional cooldown to prevent repeated webhook notifications while a rule remains matched. This is the next high-impact, small-scope feature after keyed aggregates.

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
- Only successful webhook delivery starts or refreshes cooldown.
- Failed webhook delivery leaves cooldown unchanged so later events can retry.
- Keep `matched: true` when a webhook is suppressed.
- Add `suppressedRules` to the event response so callers can see that the rule matched but its action was throttled.
- Leave `LOG` behavior unchanged because `LOG` currently only means the match is returned in the API response.
- Do not change synchronous webhook delivery or implement the transactional outbox in this slice.

## Behavior

Example: a keyed rule with a 600-second cooldown.

```text
Alice event 1 -> matched, webhook delivered
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
- Check the last successful delivery before calling `WebhookClient`.
- Update the delivery timestamp only when `WebhookClient.deliver(...)` returns success.
- Keep detection and action throttling separate: evaluation remains matched even when delivery is suppressed.

## Frontend/API Changes

- Add a numeric `Cooldown (seconds)` field to webhook rule configuration.
- Explain that cooldown controls repeated webhook delivery, not rule detection.
- Add `suppressedRules` to the event response as an additive field.
- Preserve existing response fields and behavior for rules without cooldown.

## Test Plan

- First matching webhook calls `WebhookClient` once and records delivery time.
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
- Transactional outbox, delivery history, dead-letter handling, and asynchronous retries remain later roadmap items.
