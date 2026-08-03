# Producer Response Controls and Idempotency Replay Header Plan

## Status: Implemented — 2026-08-03

## Recommendation

Implement a small producer-contract hardening slice for `POST /api/events`:

- Return an explicit `X-Idempotency-Replayed` response header.
- Let each API key select a `FULL` or `COMPACT` event-response mode.
- Make `COMPACT` the default for newly created API keys while grandfathering existing keys as `FULL` so current producers remain compatible.

This closes the smallest unfinished item in the CEP roadmap while providing immediate production value: producers can distinguish new processing from a replay, and new integrations receive a smaller, less revealing response unless an administrator explicitly selects full details.

## Goals

- Make idempotent replay observable without changing the HTTP status code or requiring producers to compare response bodies.
- Reduce response size and unnecessary exposure of event data for producers that opt into compact responses.
- Keep event persistence, rule evaluation, event traces, webhook payloads, and delivery behavior unchanged.
- Preserve the current response contract for all existing API keys.
- Store and replay the exact producer-facing response selected for the API key.

## Non-Goals

- Do not implement watermarks, allowed lateness, absence rules, broker connectors, replay, or backfill in this slice.
- Do not change rule evaluation or trigger semantics.
- Do not let an ingestion request override its API key's response mode through a header or query parameter.
- Do not add API-key editing or reactivation; response mode is selected when a key is created.
- Do not change webhook payloads or Event Explorer trace details.
- Do not change the existing `200 OK` success status or idempotency-conflict behavior.

## Producer Contract

### Replay header

Every successful `POST /api/events` response returns:

```http
X-Idempotency-Replayed: false
```

The value is `true` only when Tengen returns a previously completed response for the same API key and `Idempotency-Key` with an equivalent payload.

Behavior:

- A newly processed request returns `false`, whether or not it supplied an idempotency key.
- An equivalent retry of a completed request returns `true`.
- A conflicting or still-processing idempotency request keeps the existing `409 Conflict` behavior and does not claim a successful replay.
- Browser clients must be able to read the header through the configured CORS policy.

### Response modes

Add an API-key-scoped `responseMode` with these values:

| Mode | Behavior |
| --- | --- |
| `FULL` | Returns the existing response unchanged, including the echoed event, aggregate results, and completed sequence details. |
| `COMPACT` | Returns only `status`, `matched`, `rules`, `queuedRules`, and `suppressedRules`. It omits the echoed event, aggregate results, and sequence details. |

`COMPACT` is the database, application, and API-key form default for new keys. Requests that create an API key without `responseMode` create a `COMPACT` key. Existing keys are migrated explicitly to `FULL` so their current response contract does not change.

Example compact response:

```json
{
  "status": "accepted",
  "matched": true,
  "rules": ["High-value payment"],
  "queuedRules": ["High-value payment"],
  "suppressedRules": []
}
```

The compact response affects only the synchronous ingestion response. Tengen still persists the full event, immutable rule outcomes, aggregate or sequence details, and webhook intent required by the Event Explorer and delivery worker.

## Data Model

Add Flyway migration `V5__api_key_response_mode.sql` in an order that distinguishes existing keys from future keys:

1. Add nullable `api_keys.response_mode` without a default.
2. Backfill all existing API keys to `FULL`.
3. Set the column default to `COMPACT` and make it non-null.
4. Add a check constraint allowing only `FULL` and `COMPACT`.

Add a `ResponseMode` enum and map the new column on `ApiKey`. New or manually constructed entities use a defensive effective-value fallback of `COMPACT`; migrated existing entities contain an explicit `FULL` value.

## Backend Design

### API-key administration

- Add optional `responseMode` to `ApiKeyRequest`; normalize `null` to `COMPACT`.
- Add `responseMode` to list and creation `ApiKeyResponse` values.
- Extend `ApiKeyService.create` to persist the selected mode.
- Reject unknown response-mode values through normal request deserialization and structured `400` handling.

### Event processing result

Introduce an internal ingestion result that carries:

- the producer-facing response body;
- whether it was replayed;
- any typed full result needed internally before response projection.

Keep rule evaluation responsible for building the existing full `EventResponse`. Apply the API key's response projection only after processing has produced the complete result. This keeps persistence, event outcome tracing, metrics, and webhook construction independent from response presentation.

For idempotent requests:

1. Build the full internal event result once.
2. Project it to `FULL` or `COMPACT` using the authenticated API key.
3. Persist the exact projected JSON body in `event_idempotency.response_payload`.
4. Return that stored body unchanged on replay and set the replay flag to `true`.

Existing stored full response payloads remain readable and replayable. Do not attempt to infer or rewrite historical payloads.

### Controller and CORS

- Update `EventController` to set `X-Idempotency-Replayed` from the service result.
- Keep `200 OK` for both first processing and completed replay.
- Expose `X-Idempotency-Replayed` in `CorsConfig` so browser-based producers can inspect it.
- Define the header name once as a constant to avoid controller and test drift.

### Metrics and logs

- Preserve the existing accepted, matched, and replayed counters.
- A replay increments only the replay counter and must not increment accepted or matched counters again.
- Do not log event bodies or response bodies when selecting a response mode.

## Frontend Changes

- Add `ResponseMode`, `responseMode`, and request typing to `frontend/src/lib/types.ts`.
- Add a response-mode selector to the New API Key dialog.
- Default the selector to `Compact summary`.
- Explain the choices in producer language:
  - Full details: includes the submitted event and rule calculation details.
  - Compact summary: includes acceptance and rule/action name summaries only.
- Show the selected response mode in the API Keys table.
- Reset the selector to `COMPACT` after successful creation or dialog reset.

No frontend ingestion client is required; this UI only configures API-key behavior.

## Compatibility and Security

- Existing keys remain `FULL`, while new keys default to `COMPACT`; this changes the default for new integrations without silently changing current producers.
- The full internal processing result remains available to administration features even when the producer receives a compact body.
- Compact mode does not weaken idempotency: the request hash, unique key scope, single processing pass, and exact-response replay remain unchanged.
- Compact mode reduces response disclosure but is not a substitute for event-data authorization, TLS, or retention controls.
- The replay header reveals only processing state for the authenticated API key and supplied idempotency key.

## Implementation Sequence

### Slice 1 — persistence and API-key contract

1. Add the Flyway migration and response-mode enum.
2. Map response mode on `ApiKey` with a defensive `COMPACT` default while preserving the migration's explicit `FULL` value for existing rows.
3. Extend backend request/response DTOs and API-key creation.
4. Update frontend API-key types, creation form, and list display.

### Slice 2 — response projection and replay metadata

1. Add the compact response DTO or an equivalent explicit projection.
2. Return response body plus replay metadata from event processing.
3. Persist the selected producer-facing body for new idempotency records.
4. Replay stored bodies without reprocessing.
5. Set and expose `X-Idempotency-Replayed`.

### Slice 3 — verification and documentation

1. Add focused backend unit coverage for response projection, defaults, and replay metadata.
2. Add frontend tests for selecting and submitting both modes.
3. With explicit user approval, create or run the relevant Spring Boot integration tests for migration, first-request/replay headers, compact bodies, conflicts, and single processing.
4. Run focused frontend checks, then lint and production build.
5. Update the README and CEP roadmap after implementation is verified.

## Verification Plan

### Backend unit coverage

- A missing response mode normalizes to `COMPACT`.
- `FULL` produces the existing response shape without field loss.
- `COMPACT` contains only the documented summary fields.
- Response projection does not change the stored event trace or webhook intent.
- A newly processed result reports `replayed = false`.
- A completed idempotency replay reports `replayed = true` and returns the stored body.
- A conflicting payload keeps the existing conflict response.

### Database and integration coverage

These tests require explicit user approval before they are created or run, per repository instructions.

- The migration assigns `FULL` to existing API keys, defaults future rows to `COMPACT`, and enforces valid values.
- A request using a full key returns the unchanged full JSON shape and replay header `false`.
- A request using a compact key returns the compact JSON shape and replay header `false`.
- An equivalent retry returns the exact original body with replay header `true`.
- Only one event, evaluation trace, and webhook intent exist after an idempotent retry.
- A changed payload with the same idempotency key returns `409` and does not report a successful replay.
- A request without an idempotency key returns replay header `false`.

### Frontend coverage

- The create-key dialog defaults to `COMPACT`.
- Selecting either `COMPACT` or `FULL` sends the correct request field.
- The key list displays the configured response mode.
- Existing API-key creation and revocation behavior remains unchanged.

## Acceptance Criteria

The feature is complete when:

- Existing API keys continue receiving the unchanged full event response.
- A newly created API key defaults to compact responses when the caller or administrator does not choose a mode.
- An administrator can explicitly select either compact or full responses from the console.
- A compact key receives only the documented acceptance and rule/action summaries.
- A producer can determine from `X-Idempotency-Replayed` whether a successful response was newly processed or replayed.
- Replaying an equivalent idempotent request returns the exact original response body without duplicating events, traces, or webhook intents.
- Event Explorer and webhook delivery behavior are identical for `FULL` and `COMPACT` keys.
- Focused checks pass and any Spring Boot integration-test creation or execution has been separately approved by the user.

## Risks and Mitigations

- **Risk: changing the current response for existing producers.** Backfill existing keys to `FULL` before making `COMPACT` the database, backend, and UI default for new keys.
- **Risk: first and replay responses serialize differently.** Persist the final projected JSON and replay that stored representation unchanged.
- **Risk: compact mode accidentally removes internal data.** Project only at the producer-response boundary, after persistence and rule/action processing.
- **Risk: browsers cannot read the custom header.** Add the header to CORS exposed headers and verify it explicitly.
- **Risk: enum/schema drift.** Use the same two values in the Java enum, validation, Flyway check constraint, and frontend union type.

## Ordered Follow-Up Roadmap — Not Part of This Implementation

1. **Watermarks and allowed lateness.** Define when event-time progress is considered complete, how much lateness is accepted, and what happens to events beyond that grace period.
2. **Absence rules built on the lateness foundation.** Add negative patterns only after Tengen can safely decide that the expected event's time window has closed.
3. **Broker ingestion and replay/backfill when there is a concrete integration target.** Choose connector, offset, ordering, retry, and backfill behavior from an actual Kafka, RabbitMQ, SQS, or other target rather than building a generic abstraction prematurely.

Do not implement absence rules before defining lateness semantics. Without a watermark or equivalent closure policy, Tengen could emit an “event never happened” webhook and then receive the supposedly absent event late, creating a false alert with no defined correction behavior.
