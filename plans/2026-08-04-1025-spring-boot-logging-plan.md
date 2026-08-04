# Spring Boot Failure and Audit Logging

## Status: Planned — not currently scheduled in the CEP roadmap as of 2026-08-04

## Summary

Extend the existing SLF4J logging with searchable `key=value` events, request correlation, security auditing, and better failure context. Keep event payloads, credentials, tokens, expressions, and callback URLs out of logs.

## Implementation Changes

### Request correlation

- Add a highest-precedence request filter that:
  - Accepts `X-Request-ID` only when it matches `[A-Za-z0-9._-]{1,64}`; otherwise generates a UUID.
  - Stores it in MDC as `requestId`, returns it in the response, and clears/restores MDC in `finally`.
  - Runs before `RequestBodyLimitFilter`.
- Configure Spring Boot's `logging.pattern.correlation` to include the MDC request ID.
- Do not emit general access logs or propagate request IDs into durable webhook records; use `eventId` and `outboxId` across asynchronous boundaries.

### Logging locations and contents

| Area | Level and event | Safe fields |
|---|---|---|
| `AuthController` / auth sessions | INFO for login, refresh, and accepted logout; WARN for invalid credentials, throttling, invalid refresh, and replay; ERROR with stack trace for unexpected refresh failures currently hidden by the catch-all | actor, remote address, fixed reason code |
| API-key and JWT security filters | WARN for missing/invalid/expired credentials, rate limiting, oversized requests, unauthenticated access, and forbidden access | request method/path, key ID when resolved, content length/limit |
| `ApiExceptionHandler` | ERROR once for unexpected API failures; WARN for access denial and chunked-body limit failures; leave routine validation/404/conflict responses quiet | request ID through MDC, method, path, safe principal identifier |
| `EventService` | DEBUG for accepted events and idempotent replays; WARN for idempotency payload mismatch; INFO for a concurrent in-progress idempotency request | event ID, API-key ID, event-time status, matched/queued/suppressed counts, fixed reason |
| Rule and sequence evaluation | Improve existing WARN logs when expressions fail; retain DEBUG/WARN for malformed aggregate inputs | rule ID, revision, event ID, sequence-step position, exception type; omit expressions and event data |
| Webhook worker | Keep INFO success and ERROR claim/unexpected failures; enrich WARN failures with rule ID/revision, attempt, status, retry/dead-letter outcome, duration, and a non-sensitive failure category; WARN when lease-based finalization is skipped | outbox ID, rule ID/revision, attempt, HTTP status, duration |
| Admin mutations | INFO after service transactions return for rule create/update/toggle/archive/unarchive/restore, API-key create/revoke, and manual webhook retry | actor, action, entity ID, revision/status; never raw keys |
| Retention job | Keep INFO deletion summary; add one ERROR with stack trace and partial deletion totals if cleanup aborts | cutoff, table/batch context, rows already deleted |

- Replace existing user-controlled rule-name fields in operational logs with stable IDs.
- Use fixed event/reason names such as `security_event=admin_login_failed` and parameterized SLF4J messages.
- Do not add database logging, SQL logging, request/response body logging, or a new logging dependency.

## Interfaces

- Add optional request header and response header: `X-Request-ID`.
- Preserve all response bodies and status codes.
- If needed for safe webhook diagnostics, extend the internal `WebhookDeliveryResult` with a fixed failure-category enum; no public HTTP contract or database schema changes.

## Test Plan

- Unit-test correlation header reuse/generation, invalid-header replacement, response propagation, and MDC cleanup after success and exception.
- Capture logs to verify authentication rejection, refresh replay, idempotency conflict, rule-expression failure, webhook retry/dead-letter, stale lease, admin mutation, and retention failure events.
- Assert that raw API keys, passwords, JWTs, refresh tokens, idempotency keys, event payloads, expressions, signatures, and callback URLs never appear.
- Run backend compilation and focused unit tests only. No Spring Boot integration test will be created or run without separate approval.

## Assumptions

- Keep the current console/SLF4J format rather than switching to JSON logging.
- INFO is reserved for security-sensitive/admin successes and existing operational milestones; high-volume ingestion remains DEBUG.
- Existing Prometheus metrics remain the source for aggregate alerting, while logs provide diagnostic context.
