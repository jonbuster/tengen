# Event Idempotency Keys Plan

## Status: Implemented — integration verification pending approval

Add optional client-provided idempotency keys to `POST /api/events` so producers can safely retry requests without persisting or evaluating the same logical event more than once.

This plan is intentionally separate from event-time semantics. The event timestamp describes when an event occurred; the idempotency key identifies the producer's logical event.

## Problem

The current ingestion flow persists and evaluates every request. If a producer times out after Tengen accepts an event and retries the request, the same event can:

- be persisted more than once;
- contribute multiple times to aggregate windows;
- trigger the same webhook more than once.

The current `EventRequest` does not contain an event identity or idempotency metadata, and `EventService.process(...)` always starts a new processing flow.

## Producer Contract

Use the HTTP `Idempotency-Key` header:

```http
Idempotency-Key: payment-123
```

The producer creates the key once when it creates the logical event and stores it with the outgoing message or outbox record. Every retry of that logical event must reuse the same key.

Recommended key sources, in order:

1. A stable upstream event, transaction, or message ID.
2. A UUID or ULID generated when the event is first created and persisted by the producer.
3. A durable producer outbox record ID.

The key must not be generated on every HTTP retry. Tengen should not derive the key from the payload or timestamp because two legitimate events may have identical data.

## Behavior

Idempotency is scoped to the API key. The same key used with two different API keys represents two different namespaces.

| Request | Behavior |
|---|---|
| No `Idempotency-Key` header | Preserve current behavior; process normally without deduplication. |
| First request with a key | Process, persist, evaluate, dispatch actions, and save the response. |
| Retry with the same key and equivalent payload | Return the original response and do not persist, evaluate, or dispatch again. |
| Same key with a different payload | Return `409 Conflict`; never process the second payload. |
| Same key while the first request is still processing | Do not process concurrently; return a clear retryable conflict or wait and replay the completed response. |

Two genuine events with the same data but different timestamps must use different keys and will be processed separately:

```text
event 1: key = payment-123, timestamp = 2026-08-02T10:00:00Z
event 2: key = payment-124, timestamp = 2026-08-02T10:01:00Z
```

The timestamp remains available for event-time aggregate windows and is not used as the identity boundary.

## Data Design

Prefer a dedicated `event_idempotency` table rather than adding only a key column to `events`. A duplicate request must replay the original `EventResponse`, including aggregate results and `suppressedRules`, without re-running rule evaluation.

Suggested fields:

- `id` — generated primary key.
- `api_key_id` — owning API key.
- `idempotency_key` — producer-supplied key, length-limited.
- `request_hash` — SHA-256 hash of the canonical request payload.
- `event_id` — the event created by the first request.
- `response_payload` — original `EventResponse` stored as JSONB.
- `status` — `PROCESSING` or `COMPLETED`.
- `created_at` and `completed_at`.

Constraints and indexes:

- Unique constraint on `(api_key_id, idempotency_key)`.
- Index on `created_at` for future retention cleanup.
- Optional index on `event_id` for operational lookup.

Store the response payload because reconstructing it later could produce different aggregate values or action-suppression state.

## Request Fingerprinting

When a key is present, compute a deterministic request hash from the semantic request fields:

- `type`;
- `source`;
- `timestamp` as supplied, including whether it was omitted;
- `data` with object keys serialized in stable order.

Do not include `Idempotency-Key` or `X-API-Key` in the payload hash. The API key is already part of the database scope.

If the same key is reused with a different hash, raise an idempotency conflict. This protects producers from accidentally mapping one key to multiple events.

## Backend Changes

1. Add an `Idempotency-Key` header parameter to `EventController.ingest(...)`.
2. Validate that supplied keys are non-blank and within a documented maximum length, such as 255 characters.
3. Add `EventIdempotency` entity, repository, and enum for processing status.
4. Add a migration/schema change for the table, unique constraint, JSONB response, and indexes.
5. Add a service responsible for reserving a key, comparing request hashes, and replaying completed responses.
6. Update `EventService.process(...)` so reservation and event processing are coordinated in one safe flow.
7. Save the completed `EventResponse` after successful processing.
8. Add an `IdempotencyConflictException` and map it to `409 Conflict` through the existing API exception handling.
9. Ensure validation failures and rolled-back processing do not leave a permanently completed idempotency record.
10. Preserve the current behavior for requests without the header.

## Concurrency and Failure Handling

The unique database constraint is the final protection against two workers processing the same key at once. The implementation should handle the race where two requests both observe that a key does not yet exist.

Desired behavior:

- One request reserves and processes the key.
- A concurrent request cannot create a second reservation or evaluate the event independently.
- A completed reservation replays the stored response.
- If processing rolls back, the reservation must not block a later retry forever.
- A payload conflict is rejected even if the original request has already completed.

This feature does not provide exactly-once delivery to external webhook servers. Because webhook delivery is currently synchronous, a process failure after the remote server accepts a webhook but before the database transaction commits can still cause a duplicate delivery on retry. The transactional outbox should address that later.

## API Examples

First request:

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -H "X-API-Key: tg_..." \
  -H "Idempotency-Key: payment-123" \
  -d '{"type":"payment","source":"billing","timestamp":"2026-08-02T10:00:00Z","data":{"amount":100}}'
```

Retry the same logical event with the same `Idempotency-Key`. Tengen returns the original response without running the rule engine again.

Reusing `payment-123` with a different amount or timestamp returns `409 Conflict`.

## Documentation Changes

- Update `README.md` under **Ingesting Events** with the header contract and retry examples.
- Document that idempotency keys are optional for backward compatibility but strongly recommended for reliable producers.
- Explain that different legitimate events, even with identical data, require different keys.
- Document the conflict response and the API-key scope.
- Add the feature to the CEP roadmap as implemented after verification.

No frontend UI is required because idempotency keys are supplied by event-producing clients, not configured in the admin console.

## Test Plan

### Unit coverage

- Canonical equivalent JSON produces the same request hash.
- Changing `type`, `source`, `timestamp`, or `data` changes the hash.
- Blank and overlong keys are rejected.
- Completed records replay their stored response.
- A different payload with the same key raises a conflict.

### API/integration coverage

- A first request with a key returns `200` and creates one event.
- Retrying the exact request with the same API key returns the original response.
- Retrying does not create another event or `rule_event` row.
- Retrying does not invoke a webhook a second time.
- The same key under two different API keys is processed independently.
- The same payload with different keys creates two events and evaluates both.
- The same data with different timestamps is processed as two events when the keys differ.
- Reusing a key with a changed timestamp returns `409 Conflict`.
- A failed or rolled-back first attempt can be retried successfully.
- Concurrent requests with the same key result in one processing flow.
- Requests without an idempotency key retain existing behavior.

Before implementation, confirm approval to create or run the Spring Boot integration tests required by this plan.

## Acceptance Criteria

The feature is complete when a producer can safely retry an event using the same `Idempotency-Key`, receive the original response, and prove that Tengen created only one event, evaluated rules once, and dispatched actions once. Legitimate events with different keys remain independent even when their payloads are identical or their timestamps differ.

## Follow-up Features

After this feature, the next roadmap candidates remain:

1. `EDGE` and once-per-window trigger modes.
2. Transactional outbox with asynchronous webhook delivery and retry history.
3. Rule versioning and audit history.
