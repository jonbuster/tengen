# Unified Deliveries and Notification Outbox Explorer Plan

## Status: Proposed - 2026-08-05 21:33 PHT

This is a planning-only document. It does not implement the unified delivery
page or notification outbox APIs.

## Objective

Make the existing **Deliveries** area the single admin view for all outbound
actions:

```text
WEBHOOK | EMAIL | SMS
```

The page must show the actual delivery lifecycle, not only the synchronous rule
outcome. For example, an event can have an immutable `EMAIL_QUEUED` rule
outcome while its notification outbox row later becomes `SUBMITTED` or
`DEAD_LETTER`.

## Current state

- `frontend/src/app/deliveries/page.tsx` is explicitly named and implemented as
  **Webhook Deliveries**.
- The page calls `/api/webhook-deliveries` and uses webhook-only fields such as
  HTTP status, callback URL, and webhook payload.
- `WebhookDeliveryAdminService` supports webhook listing, detail, and retry.
- Email and SMS rows are stored in `notification_outbox`, but there is no
  notification admin controller, notification delivery service, or UI page.
- `event_rule_outcomes.action_outcome` records the rule-processing result and
  is intentionally not rewritten after asynchronous delivery.
- `notification_outbox.status` contains the later delivery state, including
  `PENDING`, `PROCESSING`, `RETRY_SCHEDULED`, `SUBMITTED`, `DELIVERED`,
  `DEAD_LETTER`, and `TEMPLATE_RENDER_ERROR`.

## Scope

### Included

- A unified `/deliveries` admin page for webhook, email, and SMS.
- A common delivery list and detail API.
- Notification outbox history and status visibility.
- Email/SMS retry controls for eligible dead-letter rows.
- Event Explorer links that show the current delivery record.
- Auto-refresh for active deliveries.
- Safe masking and redaction of recipient and provider information.
- Backward compatibility for the existing webhook delivery API during the
  transition.

### Not included

- Multiple actions per rule or fan-out changes.
- New email or SMS providers.
- SES direct API support.
- Delivery receipt webhooks from SES, Twilio, or other providers.
- Automatic retry of template-rendering errors without a valid message
  snapshot.
- Editing provider credentials from the Deliveries page.

## Product behavior

### Unified list

The page title becomes **Deliveries**. Each row must identify its channel with
a chip or icon:

```text
Webhook | Email | SMS
```

Common columns:

- status;
- channel;
- rule name;
- event ID;
- provider;
- safe destination or masked recipient;
- attempt count;
- next attempt;
- created time; and
- latest error.

Channel-specific display fields:

- Webhook: sanitized callback destination and last HTTP status.
- Email: masked recipient, sender, subject, and provider message ID when
  available.
- SMS: masked phone number, sender number, message preview, and provider
  message ID when available.

### Status semantics

The common UI must preserve the meaning of each channel's status:

- `PENDING`: waiting for the worker.
- `PROCESSING`: claimed by a worker.
- `RETRY_SCHEDULED`: failed transiently and waiting for another attempt.
- `SUBMITTED`: email/SMS provider accepted the message for processing.
- `DELIVERED`: webhook request succeeded, or a future provider receipt confirms
  delivery.
- `DEAD_LETTER`: no more automatic attempts will be made.
- `TEMPLATE_RENDER_ERROR`: the notification could not produce a valid message.

The UI must not label `SUBMITTED` as `DELIVERED`. Until provider receipt
webhooks exist, email and SMS should clearly say **Accepted by provider** or
**Submitted**, not **Delivered**.

### Filters and navigation

Provide filters for:

- channel;
- status;
- rule ID or rule name;
- event ID;
- provider/destination;
- created-time range; and
- safe destination or recipient search.

Rows link to the related event. The event detail page should link back to the
unified Deliveries page with the event filter applied.

Use a channel-qualified delivery reference because webhook ID `3` and
notification ID `3` can both exist. The API and frontend should treat a
delivery as a pair such as `EMAIL:3`, not as a globally unique numeric ID.

## Backend plan

### 1. Define common admin DTOs

Create channel-neutral response types for:

- `DeliveryChannel`: `WEBHOOK`, `EMAIL`, `SMS`;
- `DeliveryStatus`: the union of statuses needed by all channels;
- `DeliverySummary`;
- `DeliveryPage`; and
- `DeliveryDetail`.

The common summary should contain stable fields such as channel, numeric ID,
qualified ID, rule, event, status, attempts, timestamps, provider, safe
destination, and error. Detail responses can add channel-specific fields.

Do not return SMTP passwords, API tokens, encrypted credential blobs, or raw
provider secrets.

### 2. Add a unified admin API

Add JWT-protected endpoints:

```text
GET  /api/deliveries
GET  /api/deliveries/{channel}/{id}
POST /api/deliveries/{channel}/{id}/retry
```

List query parameters should support `page`, `size`, `channel`, `status`,
`ruleId`, `eventId`, `from`, `to`, and `search`.

Keep these existing webhook endpoints during the migration:

```text
/api/webhook-deliveries
/api/webhook-deliveries/{id}
/api/webhook-deliveries/{id}/retry
```

They can later become compatibility wrappers over the unified service.

### 3. Build the combined repository query

Use a backend read service rather than merging separate pages in the browser.
Browser-side merging would produce incorrect pagination and ordering.

The preferred first implementation is a parameterized native `UNION ALL` query
over `webhook_outbox` and `notification_outbox`, with a matching count query.
The query must:

- apply identical filters to both branches;
- expose a channel-qualified ID;
- normalize common timestamps and status fields;
- order deterministically by `created_at DESC, id DESC`; and
- enforce a maximum page size.

If the query becomes difficult to maintain, introduce a database view or a
dedicated read model without changing the two existing outbox write models.

### 4. Add notification delivery administration

Create `NotificationDeliveryAdminService` and controller behavior for:

- paginated notification outbox listing;
- detail lookup by notification ID;
- safe message and recipient presentation;
- retrying `DEAD_LETTER` provider failures; and
- audit logging of the admin and retry time.

Retry should set the row to `RETRY_SCHEDULED`, set `next_attempt_at` to now,
clear any expired lease, and preserve the immutable rendered message snapshot.

Do not retry `TEMPLATE_RENDER_ERROR` until there is a deliberate policy for
re-rendering after a template or recipient configuration change.

### 5. Improve Event Explorer status visibility

Preserve `EMAIL_QUEUED` and `SMS_QUEUED` as historical rule outcomes, but add
the current delivery status to the event-rule outcome response where a
notification outbox row exists.

The event detail UI should show a progression such as:

```text
Rule outcome: EMAIL_QUEUED
Delivery: Email - SUBMITTED
```

The delivery link must include the channel-qualified ID so it never opens a
webhook record with the same numeric ID.

## Frontend plan

### 1. Generalize the page

Update `frontend/src/app/deliveries/page.tsx` to:

- change the heading from `Webhook Deliveries` to `Deliveries`;
- query `/api/deliveries`;
- add a channel filter and channel column;
- support the common status union;
- retain auto-refresh for pending, processing, and retry-scheduled rows; and
- preserve server-side pagination.

### 2. Generalize the detail dialog

Render a channel-specific detail view:

- Webhook: callback host/path, HTTP result, request payload, and webhook
  timeline.
- Email: provider, masked recipients, sender, subject, text/HTML preview,
  provider message ID, and notification timeline.
- SMS: provider, masked number, sender, message preview, provider message ID,
  and notification timeline.

HTML email previews must be sanitized or rendered in an appropriately isolated
preview context. Never render provider credentials or unsanitized external
content in the admin page.

Show retry only when the row is `DEAD_LETTER` and the channel supports a safe
retry. Use a confirmation dialog and invalidate both list and detail queries
after a successful retry.

### 3. Use clear labels

Examples:

```text
SUBMITTED        Accepted by provider
RETRY_SCHEDULED  Retrying
DEAD_LETTER      Failed permanently
```

Include a short explanation that `SUBMITTED` does not guarantee inbox delivery
until provider delivery receipts are implemented.

## Data and security considerations

- Keep webhook and notification outbox writes separate to minimize migration
  risk.
- Add or verify indexes supporting channel/status, event ID, rule ID, and
  created-time ordering.
- Mask email addresses and phone numbers in list views by default.
- Sanitize callback destinations and provider errors before display.
- Truncate provider errors and message previews to bounded lengths.
- Never expose encrypted credentials, SMTP passwords, API tokens, or full
  authorization headers.
- Ensure admin endpoints use the existing JWT/admin authorization boundary.
- Audit manual retries with actor, channel, delivery ID, and timestamp.

## Implementation sequence

1. Add common backend DTOs, enums, and read-service contracts.
2. Implement notification outbox administration and retry behavior.
3. Implement the combined paginated list/detail API while preserving webhook
   endpoints.
4. Update Event Explorer responses and links with current delivery status.
5. Convert the frontend page to the unified list and filters.
6. Add channel-specific detail views, safe previews, and retry actions.
7. Add focused tests and run the existing backend/frontend suites.
8. Perform a manual smoke test for webhook, email submitted, email dead-letter,
   SMS submitted, and retry flows.

## Verification plan

### Backend

- List pagination returns correctly ordered rows from both outboxes.
- Channel and status filters apply to both webhook and notification rows.
- Event, rule, date, and search filters do not leak rows across branches.
- Numeric ID collisions are resolved by channel-qualified IDs.
- Notification detail never returns provider credentials.
- Email/SMS dead-letter retry schedules the row immediately.
- Template-render errors are not incorrectly retried.
- Existing webhook list, detail, and retry behavior remains unchanged.

### Frontend

- Deliveries page renders mixed webhook/email/SMS rows.
- Status chips and channel labels are correct.
- Email and SMS details do not show secrets and render safe previews.
- Auto-refresh stops when no active rows remain.
- Retry invalidates list/detail state and shows the updated status.
- Event Explorer links to the correct channel-specific delivery.

### Acceptance criteria

- An email event can be traced from `EMAIL_QUEUED` to `SUBMITTED` or
  `DEAD_LETTER` from the Deliveries page without SQL access.
- A failed email or SMS delivery exposes a safe provider error and can be
  manually retried when eligible.
- Webhook delivery history continues to work in the same page.
- No credential or sensitive provider configuration is exposed in list/detail
  responses.
