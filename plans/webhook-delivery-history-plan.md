# Webhook Delivery History Plan

## Status: Planned — depends on durable outbox and delivery worker

Add JWT-protected admin APIs and a Next.js page for inspecting webhook delivery state and manually retrying dead-lettered work. This is the third asynchronous delivery slice.

## Problem

Durable background delivery is difficult to operate if status and failures are visible only in application logs or directly in PostgreSQL. Admins need to answer:

- Was a webhook queued, delivered, retried, or dead-lettered?
- Which rule and event produced it?
- How many attempts were made and when is the next one?
- What was the latest HTTP status or error?
- Can a corrected endpoint be retried safely?

## Goal

Provide searchable delivery history and a controlled manual retry without exposing sensitive payloads by default or creating duplicate logical deliveries.

## Scope

- Add paginated, filterable admin APIs over `webhook_outbox`.
- Add delivery summary and detail DTOs.
- Add a `/deliveries` page to the Next.js admin console.
- Show delivery state, rule, event, attempt count, timestamps, destination, and last failure.
- Allow an admin to requeue a `DEAD_LETTER` delivery.
- Keep access behind the existing JWT admin authentication.

## Out of Scope

- Editing queued payloads or destinations.
- Bulk retry or bulk deletion in the initial version.
- Public delivery-status APIs for event producers.
- Full per-attempt audit rows; the initial version shows count and latest result.
- Automatic retention/deletion.
- Role-based admin permissions beyond the current single-admin model.

## Admin API

Add a controller under `/api/webhook-deliveries`.

### List deliveries

```http
GET /api/webhook-deliveries?page=0&size=25&status=DEAD_LETTER&ruleId=12
```

Supported filters:

- `status`;
- `ruleId`;
- `eventId`;
- `from` and `to` creation timestamps;
- optional text search across rule name and destination host.

Default ordering: newest `createdAt` first. Cap page size, for example at 100.

### Delivery detail

```http
GET /api/webhook-deliveries/{id}
```

Return the full delivery metadata and payload only on the detail endpoint. Consider redacting configured sensitive field names before returning payload data.

### Manual retry

```http
POST /api/webhook-deliveries/{id}/retry
```

Behavior:

- allow only `DEAD_LETTER` rows;
- update the same outbox row to `RETRY_SCHEDULED`;
- reset `nextAttemptAt` to now and clear lease/error fields as appropriate;
- retain the cumulative attempt count and set `manuallyRetriedAt`;
- reject `PROCESSING` or `DELIVERED` rows with `409 Conflict`;
- make concurrent retry requests idempotent through row locking.

Do not create a new outbox row or a new deduplication key.

## DTOs

Suggested summary fields:

- `id`;
- `status`;
- `ruleId` and `ruleName`;
- `eventId`;
- redacted or host-only callback destination;
- `scopeKey` where safe;
- `triggerMode`;
- `attemptCount`;
- `nextAttemptAt`, `lastAttemptAt`, `deliveredAt`, and `createdAt`;
- latest HTTP status and a bounded error summary.

The detail DTO can additionally include:

- full callback URL for authenticated admins;
- stored payload;
- deduplication key;
- window start;
- lease/recovery metadata useful for troubleshooting.

Do not serialize JPA entities directly.

## Backend Changes

1. Add repository specifications or explicit queries for pagination and filters.
2. Add summary/detail response DTOs.
3. Add `WebhookDeliveryAdminService` for queries and transactional manual retry.
4. Add `WebhookDeliveryAdminController` under the JWT-protected route.
5. Extend `SecurityConfig` so `/api/webhook-deliveries/**` requires admin JWT authentication.
6. Validate timestamps, page bounds, enum values, and retry state transitions.
7. Add consistent `404` and `409` responses through `ApiExceptionHandler`.
8. Avoid lazy-loading failures by projecting required rule/event metadata explicitly.

## Frontend Changes

Add a `Deliveries` item to the main navigation and create `/deliveries`.

### List page

- MUI Data Grid with server-side pagination.
- Status chips for `PENDING`, `PROCESSING`, `RETRY_SCHEDULED`, `DELIVERED`, and `DEAD_LETTER`.
- Filters for status, rule, event ID, and date range.
- Columns for created time, rule, event ID, destination host, status, attempts, next attempt, and last result.
- Auto-refresh while any visible rows are pending or processing, with a modest interval such as five seconds.
- Empty, loading, and API-error states consistent with the current rules and keys pages.

### Detail view

- Delivery timeline using existing timestamps.
- Callback destination and immutable trigger metadata.
- Formatted JSON payload in a collapsed section.
- Latest error/status information.
- `Retry` button only for `DEAD_LETTER` rows.
- Confirmation dialog before retrying.

Add TypeScript types for page responses, delivery summary/detail, status, and retry response. Use TanStack Query for fetching, cache invalidation, and the retry mutation.

## Security and Privacy

- Keep every endpoint behind JWT admin authentication.
- Never expose API-key hashes, JWTs, or cookie values.
- Avoid showing callback URL credentials or sensitive query parameters in list results.
- Bound error messages returned to the browser.
- Render payload JSON as text, never as HTML.
- Consider configurable payload-field redaction before enabling this page for production data.

## Test Plan

### Backend

- Anonymous access is rejected.
- List results are paginated, ordered, and filter correctly.
- Detail returns `404` for an unknown delivery.
- Manual retry changes `DEAD_LETTER` to `RETRY_SCHEDULED` on the same row.
- Retry of `DELIVERED`, `PROCESSING`, or already-requeued work returns `409`.
- Concurrent retry requests cannot produce duplicate work.
- DTOs do not expose internal entity fields or secrets.

### Frontend

- Each status renders the expected chip and timestamps.
- Filters and pagination are sent to the API.
- Auto-refresh is active only when useful.
- Detail JSON is escaped safely.
- Retry confirmation invokes the endpoint and refreshes cached list/detail data.
- Loading, empty, error, and unauthorized states render correctly.
- Run the frontend lint/build checks available in `frontend/package.json`.

Spring Boot integration tests must not be created or run until the user approves them, per the repository instructions.

## Acceptance Criteria

This slice is complete when an authenticated admin can find a delivery, understand its current and latest failure state, correlate it to its rule and event, and safely requeue a dead-lettered delivery without creating a second logical outbox item.

## Follow-up Features

- Per-attempt audit records and response-header snapshots.
- Bulk retry with strict confirmation and limits.
- Retention policies and archive export.
- Metrics dashboard and alerts for backlog age/dead-letter growth.
- Event Explorer linking event details to related webhook deliveries.
