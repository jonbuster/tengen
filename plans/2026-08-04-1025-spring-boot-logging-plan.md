# Spring Boot Failure and Audit Logging

## Status: Implemented — 2026-08-04

## Summary

Extend the existing SLF4J logging with searchable `key=value` events, request correlation, security auditing, and better failure context. Keep event payloads, credentials, tokens, expressions, and callback URLs out of logs.

## Scope Refresh After Later Roadmap Slices

This plan was drafted before the replay controls/history and RabbitMQ connector
slices were completed. The implementation must now cover both HTTP and broker
ingestion while preserving the existing database audit histories.

- HTTP ingestion still uses API-key authentication, idempotency, event-time
  classification, and the event response projection.
- RabbitMQ ingestion uses durable message receipts, connector lifecycle state,
  bounded retries, dead-letter categories, and the optional
  `x-tengen-watermark: false` opt-out. Broker logs must use connector/message
  metadata and fixed categories only; never log message bodies, arbitrary
  headers, passwords, or API-key values.
- Replay controls already emit one committed transition log. Preserve that
  after-commit behavior, add equivalent coverage for replay creation and worker
  lifecycle failures, and include request correlation for HTTP-triggered actions.
- Rule revisions and replay transitions are durable audit records. This feature
  adds operational/security diagnostics, not a new audit-table schema.

## Implementation Changes

### Request correlation

- Add a highest-precedence request filter that:
  - Accepts `X-Request-ID` only when it matches `[A-Za-z0-9._-]{1,64}`; otherwise generates a UUID.
  - Stores it in MDC as `requestId`, returns it in the response, and clears/restores MDC in `finally`.
  - Runs before `RequestBodyLimitFilter` and the Spring Security filters.
    `RequestBodyLimitFilter` currently uses `Ordered.HIGHEST_PRECEDENCE`, so
    assign the correlation filter that order and move the body-limit filter to
    `Ordered.HIGHEST_PRECEDENCE + 1`.
- Configure Spring Boot's `logging.pattern.correlation` to include the MDC request ID.
- Do not emit general access logs or propagate request IDs into durable webhook records; use `eventId` and `outboxId` across asynchronous boundaries.

### Logging locations and contents

| Area | Level and event | Safe fields |
|---|---|---|
| `AuthController` / auth sessions | INFO for login, refresh, and accepted logout; WARN for invalid credentials, throttling, invalid refresh, and replay; ERROR with stack trace for unexpected refresh failures currently hidden by the catch-all | actor when safely known, remote address, fixed reason code |
| API-key and JWT security filters plus `SecurityConfig` handlers | WARN for missing/invalid/expired credentials, rate limiting, oversized requests, unauthenticated access, and forbidden access. The Spring Security entry point and access-denied handler must be included because they currently terminate requests with `sendError` outside the controller advice. | request method/path, key ID when resolved, content length/limit, safe principal |
| `ApiExceptionHandler` | ERROR once for unexpected API failures; WARN for access denial and chunked-body limit failures; leave routine validation/404/conflict responses quiet | request ID through MDC, method, path, safe principal identifier |
| `EventService` and `RabbitMqMessageProcessingService` | DEBUG for accepted events, idempotent replays, broker deduplication, and watermark decisions; WARN for idempotency payload mismatch and permanent broker failures; INFO only for a concurrent in-progress idempotency request or significant broker state changes | event ID, origin, connector ID, message ID, API-key ID, event-time status, `watermarkApplied`, matched/queued/suppressed counts, fixed reason/category |
| Rule, sequence, and absence evaluation | Improve existing WARN logs when expressions fail; retain DEBUG/WARN for malformed aggregate inputs and missing/invalid group keys. Include the startup rule-validation audit in the safe logging review. | rule ID, revision, event ID, sequence-step position, exception type; omit rule names, expressions, and event data |
| Webhook worker | Keep successful delivery at DEBUG; keep ERROR for claim/unexpected failures; enrich WARN failures with rule ID/revision, attempt, status, retry/dead-letter outcome, duration, and a non-sensitive failure category; WARN when lease-based finalization is skipped | outbox ID, rule ID/revision, attempt, HTTP status, duration; never use the user-controlled rule name or callback URL |
| Admin mutations | INFO after service transactions return for rule create/update/toggle/archive/unarchive/restore, API-key create/revoke, manual webhook retry, replay create/control, and RabbitMQ connector save/test/enable/disable | actor, action, entity ID, connector ID, revision/status, fixed outcome/category; never raw keys or passwords |
| Retention job | Keep INFO deletion summary; add one ERROR with stack trace and partial deletion totals if cleanup aborts | cutoff, table/batch context, rows already deleted |

- Replace existing user-controlled rule-name fields in operational logs with stable IDs.
- Use fixed event/reason names such as `security_event=admin_login_failed` and parameterized SLF4J messages.
- Keep asynchronous correlation on `eventId`, `outboxId`, `messageId`, and
  `connectorId`; do not attempt to carry an HTTP request ID into webhook or
  RabbitMQ delivery records.
- Do not add database logging, SQL logging, request/response body logging, or a new logging dependency.

### Log-volume controls

- Keep high-volume success paths at `DEBUG`, including accepted ingestion,
  broker deduplication, watermark decisions, webhook claims, and successful
  webhook delivery. Do not emit general access logs.
- Add bounded warning coalescing/rate limiting for repetitive categories such
  as authentication rejection, rule-expression failure, RabbitMQ processing
  failure, and webhook failure. A repeated warning for the same stable entity
  and category should be emitted at most once per short fixed window (for
  example, 60 seconds), while counters continue recording every occurrence.
- Keep `ERROR` events unsuppressed so unexpected failures retain their stack
  traces. Use a bounded or expiring suppression store; it must not grow with
  untrusted request, message, or payload values.

## Interfaces

- Add optional request header and response header: `X-Request-ID`.
- Preserve all response bodies and status codes.
- If needed for safe webhook diagnostics, extend the internal `WebhookDeliveryResult` with a fixed failure-category enum; no public HTTP contract or database schema changes.

## Test Plan

- Unit-test correlation header reuse/generation, invalid-header replacement, response propagation, and MDC cleanup after success and exception.
- Capture logs to verify authentication rejection, refresh replay, idempotency conflict, HTTP and RabbitMQ ingestion outcomes, watermark opt-out, rule-expression failure, webhook retry/dead-letter, stale lease, replay lifecycle, connector mutation, admin mutation, and retention failure events.
- Verify successful webhook delivery is logged at `DEBUG`, and repeated
  warning categories are coalesced without reducing their metrics.
- Assert that raw API keys, passwords, JWTs, refresh tokens, idempotency keys, event payloads, expressions, signatures, and callback URLs never appear.
- Run backend compilation and focused unit tests only. No Spring Boot integration test will be created or run without separate approval.

## Assumptions

- Keep the current console/SLF4J format rather than switching to JSON logging.
- INFO is reserved for security-sensitive/admin successes and existing operational milestones; high-volume ingestion remains DEBUG.
- Existing Prometheus metrics remain the source for aggregate alerting, while logs provide diagnostic context.
- README and roadmap status entries were updated after implementation.
