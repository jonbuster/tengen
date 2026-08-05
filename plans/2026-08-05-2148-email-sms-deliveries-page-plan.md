# Email and SMS Deliveries Page Plan

## Status: Proposed - 2026-08-05 21:48 PHT

This plan supersedes the unified Deliveries plan. It does not modify the
existing webhook Deliveries page and does not implement the new page yet.

## Decision

Keep the existing webhook page focused on webhook delivery history:

```text
/deliveries              Webhook Deliveries
```

Create a separate admin page for notification delivery history:

```text
/notification-deliveries Email & SMS Deliveries
```

This page will show both `EMAIL` and `SMS` rows because they share the same
notification outbox, worker lifecycle, retry model, and admin concerns. It will
not combine webhook rows with notification rows.

## Objective

Give administrators a reliable way to answer:

- Did the email or SMS leave Tengen?
- Which provider connection handled it?
- Was it submitted, retried, or dead-lettered?
- What error did the provider return?
- Which event, rule, template, and recipient produced it?
- Can this failed notification be retried safely?

The page must distinguish the synchronous rule outcome from asynchronous
delivery state. For example:

```text
Rule outcome: EMAIL_QUEUED
Delivery status: SUBMITTED
```

## Current state

- `notification_outbox` already stores the rendered email/SMS message snapshot,
  recipients, provider, status, attempt count, timestamps, and error.
- `NotificationDeliveryWorker` already claims rows and submits them to the
  configured provider.
- Notification statuses include `PENDING`, `PROCESSING`,
  `RETRY_SCHEDULED`, `SUBMITTED`, `DELIVERED`, `DEAD_LETTER`, and
  `TEMPLATE_RENDER_ERROR`.
- `frontend/src/app/notifications/page.tsx` manages provider connections and
  templates but does not show delivery history.
- `frontend/src/app/deliveries/page.tsx` and `/api/webhook-deliveries` are
  webhook-specific and should remain unchanged in this feature.
- Event Explorer currently displays `EMAIL_QUEUED` or `SMS_QUEUED`, but does not
  show the later notification outbox status.

## Scope

### Included

- New `/notification-deliveries` admin page for email and SMS.
- Email/SMS list, filters, server-side pagination, and auto-refresh.
- Email/SMS detail view with delivery timeline and provider error.
- Manual retry for eligible `DEAD_LETTER` rows.
- Backend list/detail/retry APIs for `notification_outbox`.
- Event Explorer links to the notification delivery record.
- Safe masking of recipients and redaction of provider secrets.
- Navigation from the Notifications page and main navigation.

### Not included

- Changes to the webhook Deliveries page or webhook delivery API.
- Multiple actions per rule or fan-out behavior.
- New providers or direct SES API support.
- SES/Twilio delivery receipt webhooks.
- Automatic re-rendering of failed templates.
- Provider credential editing from the delivery page.

## Page design

### Header and navigation

Page title:

```text
Email & SMS Deliveries
```

Add a link from the Notifications page and a navigation item near
Notifications. Do not rename or repurpose the existing Webhook Deliveries
navigation item.

### Channel controls

Use tabs or a channel selector:

```text
All notifications | Email | SMS
```

The selected channel should be reflected in the URL query string so a filtered
view can be bookmarked or linked from Event Explorer.

### List columns

Common columns:

- status;
- channel;
- rule name;
- event ID;
- provider;
- masked recipient or recipient count;
- attempt count;
- next attempt;
- created time; and
- latest error.

Email-specific columns or compact metadata:

- subject;
- masked To address;
- sender address; and
- provider message ID.

SMS-specific columns or compact metadata:

- masked phone number;
- sender number or sender label; and
- provider message ID.

### Filters

Support:

- channel: `EMAIL` or `SMS`;
- status;
- rule ID or rule name;
- event ID;
- provider or connection name;
- recipient search using masked-safe matching where possible; and
- created-time range.

Use server-side filtering and pagination. Do not load the entire outbox into
the browser and filter locally.

### Status presentation

Use explicit labels:

```text
PENDING            Waiting
PROCESSING         Sending
RETRY_SCHEDULED    Retrying
SUBMITTED          Accepted by provider
DELIVERED          Delivered
DEAD_LETTER        Failed permanently
TEMPLATE_RENDER_ERROR Template error
```

`SUBMITTED` must not be presented as `DELIVERED`. Email/SMS will normally stop
at `SUBMITTED` until delivery receipt webhooks are implemented.

## Backend plan

### 1. Add notification admin DTOs

Create notification-specific response types rather than reusing webhook DTOs:

- `NotificationDeliverySummary`;
- `NotificationDeliveryPage`;
- `NotificationDeliveryDetail`; and
- a notification delivery status type shared with the frontend.

Summary fields should include:

- notification ID;
- channel;
- status;
- event ID;
- rule ID, revision, and name;
- provider and safe connection/display name;
- masked recipient metadata;
- attempt count;
- next/last attempt timestamps;
- submitted/delivered timestamps;
- provider message ID; and
- bounded latest error.

Detail fields may add:

- template ID and version;
- destination ID and safe configuration metadata;
- masked recipient list;
- email subject or SMS body preview;
- plain-text body;
- sanitized HTML preview for email; and
- trigger scope/cooldown metadata.

Never return SMTP passwords, API tokens, encrypted credential blobs, or raw
authorization headers.

### 2. Add notification delivery endpoints

Add JWT-protected endpoints:

```text
GET  /api/notification-deliveries
GET  /api/notification-deliveries/{id}
POST /api/notification-deliveries/{id}/retry
```

List parameters:

```text
page, size, channel, status, ruleId, eventId,
provider, from, to, search
```

The API queries only `notification_outbox`; no union with
`webhook_outbox` is needed.

### 3. Implement the admin query service

Add `NotificationDeliveryAdminService` and a repository query strategy that
supports server-side pagination and filters. It should:

- order by `created_at DESC, id DESC`;
- enforce a maximum page size;
- filter by email/SMS channel and status;
- join only safe provider/destination metadata;
- mask recipients before building responses; and
- return `404` for missing notification rows.

Use existing outbox indexes and add focused indexes only if query plans show a
need for them. Do not change the outbox write path just to support the page.

### 4. Implement notification retry

Allow retry only for notification rows in `DEAD_LETTER` caused by provider
submission failure. A retry should:

- lock the row in a transaction;
- set `status = RETRY_SCHEDULED`;
- set `next_attempt_at = now`;
- clear lease fields;
- set `manually_retried_at`;
- preserve the rendered message snapshot; and
- write an audit log with actor, notification ID, channel, and status.

Do not retry `TEMPLATE_RENDER_ERROR` until the product has a re-render policy.
Do not create a new event or alter the original rule outcome when retrying.

### 5. Add current status to Event Explorer

Keep `EMAIL_QUEUED` and `SMS_QUEUED` as immutable event-rule outcomes. Add the
related notification delivery status and ID to the event detail response when
available:

```text
actionOutcome: EMAIL_QUEUED
notificationDeliveryId: 3
notificationDeliveryStatus: SUBMITTED
```

The Event Explorer should link to:

```text
/notification-deliveries?eventId=409252&selectedId=3
```

This removes the ambiguity between queued rule processing and actual provider
submission.

## Frontend plan

### 1. Create a separate page

Add:

```text
frontend/src/app/notification-deliveries/page.tsx
```

The page should follow the existing MUI, TanStack Query, Data Grid, filter,
auto-refresh, and preference patterns used by the webhook page, while using
notification-specific types and API paths.

The existing `/deliveries` page should remain a webhook page for this feature.

### 2. Detail panel

Email details:

- status and attempt count;
- provider and connection name;
- masked recipient and sender;
- subject;
- plain-text body;
- sanitized or isolated HTML preview;
- submitted/provider message ID;
- created, attempted, submitted, and delivered timestamps; and
- latest provider error.

SMS details:

- status and attempt count;
- provider and connection name;
- masked recipient and sender;
- body preview;
- submitted/provider message ID;
- timestamps; and
- latest provider error.

Use a text-safe preview for message content by default. Any HTML preview must
be sanitized or isolated and must never receive provider credentials or access
to the parent admin session.

### 3. Retry interaction

Show a `Retry delivery` button only for eligible `DEAD_LETTER` rows. Require
confirmation, display the server response, refresh the detail, and refresh the
list. Show the next attempt time after the row becomes `RETRY_SCHEDULED`.

### 4. Loading and failure states

Support:

- empty Email/SMS outbox;
- filter-specific empty results;
- worker-in-progress statuses;
- provider error display;
- API authorization errors; and
- manual refresh plus optional auto-refresh.

Auto-refresh should poll while rows are `PENDING`, `PROCESSING`, or
`RETRY_SCHEDULED`, and stop when the current result contains no active rows.

## Security and privacy

- Keep the page JWT/admin protected.
- Mask email addresses in list views and phone numbers except for a safe suffix.
- Do not expose SMTP credentials, Twilio tokens, encrypted values, or secret
  configuration keys.
- Sanitize provider error strings before display.
- Bound body previews and error lengths to prevent oversized responses.
- Treat email/SMS bodies and recipients as potentially sensitive admin data.
- Avoid logging full recipients or rendered message bodies.
- Preserve existing provider connection encryption and secret-handling rules.

## Implementation sequence

1. Add notification admin DTOs and status projection.
2. Add notification list/detail/retry backend services and controllers.
3. Add Event Explorer notification delivery status and links.
4. Add frontend types and `/notification-deliveries` page.
5. Add navigation links from Notifications and the main navigation.
6. Add detail previews, status timeline, filters, auto-refresh, and retry UX.
7. Add focused backend/frontend tests.
8. Run a manual test with email `SUBMITTED`, email `DEAD_LETTER`, SMS rows, and
   manual retry while confirming webhook Deliveries remains unchanged.

## Verification plan

### Backend

- List returns email and SMS rows with correct pagination.
- Channel, status, event, rule, provider, date, and search filters work.
- Detail responses contain no credentials or encrypted secret material.
- Recipient masking is consistent in list and detail responses.
- Only eligible dead-letter notification rows can be retried.
- Retry preserves the rendered snapshot and does not create a duplicate event.
- Event outcomes expose the linked notification status without changing the
  original `EMAIL_QUEUED` or `SMS_QUEUED` value.
- Existing webhook delivery endpoints and tests remain unchanged.

### Frontend

- The new page renders Email, SMS, and All notification views.
- Status chips correctly distinguish `SUBMITTED` from `DELIVERED`.
- Event links open the correct notification delivery.
- Detail views are channel-specific and safe.
- Retry updates the row and detail state.
- Existing webhook Deliveries page behavior is unchanged.

## Acceptance criteria

- An administrator can open **Email & SMS Deliveries** and see the actual
  notification outbox status without using SQL.
- Event `EMAIL_QUEUED` can be traced to `SUBMITTED`, `RETRY_SCHEDULED`, or
  `DEAD_LETTER` from the new page.
- Failed email/SMS provider deliveries can be manually retried when eligible.
- The existing webhook Deliveries page remains separate and functional.
- No provider credentials or unsafe secret configuration is exposed.
