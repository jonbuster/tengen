# Email and SMS Action System Plan

## Status: Proposed — 2026-08-05 16:01 PHT

This is a planning-only document. It does not implement email or SMS actions.

## Scope decision

For this release, a rule still has exactly one action. The supported choices
will become:

```text
LOG | WEBHOOK | EMAIL | SMS
```

This plan intentionally does not introduce multiple action bindings, fan-out,
chat actions, incident actions, queue publishing, or a generic integration
marketplace. Email and SMS are separate action choices that reuse a common
notification foundation.

Existing `LOG` and `WEBHOOK` behavior must remain compatible. A new email or SMS
action must be durable and asynchronous like the current webhook worker; event
ingestion must never wait for an SMTP server, email API, or SMS provider.

## Executive recommendation

Build one small, channel-neutral notification subsystem, then add two adapters:

1. A template compiler/renderer with a strict `{{data.first_name}}`-style
   variable syntax.
2. A durable notification outbox and worker with leases, retries, idempotency,
   dead-lettering, and delivery history.
3. An email adapter that sends multipart text plus sanitized HTML/CSS.
4. An SMS adapter that sends validated plain text with provider-aware segment
   and consent controls.

Do not put email addresses, phone numbers, SMTP credentials, provider API keys,
subjects, or message bodies directly into the `rules` table. Rules should point
to a destination and a versioned template, while queued notification rows keep
immutable snapshots so later edits cannot change already-created messages.

## Current foundation and change points

The current implementation already contains most of the reliability primitives:

- [`RuleAction`](../tengen/src/main/java/com/tengencorp/tengen/entity/RuleAction.java)
  currently contains `LOG` and `WEBHOOK`.
- [`RuleValidationService`](../tengen/src/main/java/com/tengencorp/tengen/service/RuleValidationService.java)
  validates webhook callback URLs and trigger constraints.
- [`EventService`](../tengen/src/main/java/com/tengencorp/tengen/service/EventService.java)
  records rule outcomes and enqueues webhook work for matches.
- [`WebhookOutboxService`](../tengen/src/main/java/com/tengencorp/tengen/service/WebhookOutboxService.java)
  and [`WebhookDeliveryWorker`](../tengen/src/main/java/com/tengencorp/tengen/service/WebhookDeliveryWorker.java)
  provide transactional intent creation, leasing, retries, and dead-lettering.
- [`WebhookDeliveryAdminService`](../tengen/src/main/java/com/tengencorp/tengen/service/WebhookDeliveryAdminService.java)
  provides the existing delivery-history pattern to follow for notifications.
- [`RuleForm`](../frontend/src/components/RuleForm.tsx) and
  [`frontend/src/lib/types.ts`](../frontend/src/lib/types.ts) currently expose
  only `LOG` and `WEBHOOK`.

The implementation should reuse the current rule trigger semantics, event
trace, idempotency, cooldown, EDGE, once-per-window, absence, and revision
behavior. It should add a notification-specific delivery path rather than
calling an email or SMS provider from `EventService` directly.

## Proposed architecture

### Rule evaluation and notification delivery stay separate

The synchronous transaction should do this:

```text
persist event
    -> evaluate rule
        -> determine match and trigger eligibility
            -> render/validate notification intent
                -> commit notification outbox row
                    -> return accepted response
```

The asynchronous worker should do this later:

```text
claim notification row
    -> call email or SMS provider
        -> record provider result
            -> retry, submit, deliver, or dead-letter
```

The notification intent must be committed with the event, rule outcome, and
trigger reservation. If the provider is unavailable, the event is still
accepted and the notification remains recoverable.

### One action per rule, shared notification configuration

Add notification references without introducing a `rule_actions` collection:

- `action = EMAIL` or `action = SMS` selects the channel.
- `notification_destination_id` points to the provider configuration.
- `notification_template_id` plus version points to an immutable template.
- A small rule-notification configuration stores recipient mode and recipient
  values for that rule/action.
- Existing `callbackUrl`, `triggerMode`, and `cooldownSeconds` continue to apply
  to `WEBHOOK` and are reused for `EMAIL`/`SMS` where valid.

This leaves a future path to multiple actions, but that future refactor is out
of scope now.

## Data model

Names below are proposed and should be confirmed before a Flyway migration.

### `notification_destinations`

One provider connection/configuration:

- `id` and stable display name;
- `channel`: `EMAIL` or `SMS`;
- `provider`: `SMTP`, `EMAIL_API`, `SMS_API`, or a concrete adapter name;
- non-secret provider configuration stored as structured JSON or normalized
  columns;
- encrypted credential material or a secret-manager reference;
- encryption/key version and rotation metadata;
- enabled state;
- last connection-test result and error category;
- per-destination rate limit, concurrency, and daily budget where applicable;
- created/updated/last-used timestamps; and
- workspace/owner scope if workspace isolation exists later.

Examples of non-secret configuration:

Email:

- SMTP host, port, TLS mode, and username;
- verified sender address/name;
- optional reply-to address;
- provider API region or endpoint; and
- allowed recipient/domain policy.

SMS:

- sender number, short code, or sender ID;
- provider region/country configuration;
- supported destination countries; and
- maximum segments or spend policy.

Never return passwords, API keys, tokens, or decrypted secret references to the
browser. A destination response should expose only `credentialConfigured`,
`lastTestedAt`, and safe status/category fields.

### `notification_templates`

Use immutable versions rather than editing the text of a template in place:

- template ID and human-readable name;
- channel: `EMAIL` or `SMS`;
- version number;
- active/archived state;
- text template;
- email subject template when channel is `EMAIL`;
- email HTML template and optional CSS source when channel is `EMAIL`;
- allowed variable metadata captured at compile time;
- max rendered size and segment policy;
- creator, created time, and change reason; and
- sample event/test payload metadata.

A rule references a specific version. Publishing a new template version should
not change the content of queued notifications or historical delivery details.

### `rule_notification_config`

One row per rule revision for the selected `EMAIL` or `SMS` action:

- rule ID and rule revision;
- destination ID;
- template ID and template version;
- recipient mode: `STATIC` or a future controlled `EVENT_FIELD` mode;
- encrypted or protected recipient values for static email addresses/phone
  numbers;
- optional recipient field path, initially disabled by default;
- optional sender/reply-to override subject to destination policy;
- enabled state; and
- configuration timestamps.

For the first implementation, support static recipients. The schema may reserve
an event-field path, but dynamic recipients should not be enabled until address
and phone extraction, authorization, PII handling, and audit semantics are
complete.

### `notification_outbox`

Create a notification-specific outbox rather than renaming the existing webhook
table in the first slice. This limits migration risk and preserves current
webhook history.

Each row represents one logical email or SMS notification for the one action on
one matched event:

- notification ID;
- event ID, rule ID, rule revision, rule name, and action channel;
- destination ID/provider snapshot;
- template ID/version snapshot;
- recipient snapshot, stored encrypted or protected from normal admin display;
- rendered subject/text/HTML message snapshot as needed for retry;
- trigger scope, group key, window start, and cooldown metadata;
- stable deduplication key;
- status;
- attempt count, next attempt, last attempt, submitted/delivered time;
- provider message/request ID;
- bounded provider status/error category and safe error detail;
- lease token/expiry; and
- created/updated/manual-retry timestamps.

Suggested status model:

```text
PENDING -> PROCESSING -> SUBMITTED -> DELIVERED
                    \-> RETRY_SCHEDULED -> PROCESSING
                    \-> DEAD_LETTER
                    \-> FAILED
```

`SUBMITTED` is important for email and SMS. SMTP/provider acceptance does not
always mean the recipient received the message. Use `DELIVERED` only when the
provider supplies a delivery receipt and Tengen ingests it. If the first email
adapter has no receipt integration, its successful terminal state should be
`SUBMITTED`, with the limitation visible in the UI.

`FAILED` is for a permanent rendering or configuration failure where no more
provider attempts should occur. `DEAD_LETTER` is for exhausted or operationally
recoverable delivery work that an administrator may retry under policy.

### `notification_attempts` (recommended)

Keep one immutable attempt row per provider call if delivery history matters:

- notification ID and attempt number;
- started/completed time and duration;
- provider status and message ID;
- retryable/permanent category;
- bounded safe error; and
- safe response metadata.

Do not store authorization headers, provider credentials, full provider payloads,
or arbitrary response headers. The outbox row can retain the latest summary while
attempt rows preserve the timeline.

### `notification_preferences` (SMS prerequisite)

Before SMS is enabled for real recipients, add a minimal transactional consent
and opt-out model:

- normalized phone number or recipient identity;
- channel and purpose/category;
- opted-in/opted-out state;
- source and timestamp;
- last opt-out keyword/provider event; and
- audit metadata.

Do not treat a rule's static recipient list as proof of SMS consent.

## Templating system

### Syntax

Support a deliberately small Mustache-style syntax first:

```text
Hello {{data.first_name}},
Your order {{data.order_id}} is now {{data.status}}.
```

Nested paths are supported:

```text
{{data.customer.first_name}}
{{data.order.total}}
```

The exact user-facing syntax should remain `{{data.first_name}}`; do not require
users to write implementation-specific object names for common event fields.

### Supported context

Expose only safe, documented values:

| Variable | Meaning |
| --- | --- |
| `data.*` | Current event payload fields, including nested paths. |
| `event.type` | Current event type. |
| `event.source` | Current event source. |
| `event.timestamp` | Event occurrence time in the configured display/format policy. |
| `event.id` | Internal event ID, only if the template policy allows it. |
| `rule.name` | Matched rule name. |
| `rule.revision` | Matched rule revision. |
| `match.groupKey` | Resolved aggregate/sequence/absence group key when present. |
| `match.aggregateValue` | Aggregate result when the rule is an aggregate rule. |
| `match.threshold` | Configured aggregate threshold when applicable. |
| `match.sequence` | Safe, structured sequence summary when applicable. |
| `match.absence` | Safe absence summary when applicable. |
| `delivery.id` | Stable notification ID for recipient/provider deduplication. |

Do not expose API keys, JWTs, cookies, callback credentials, SMTP credentials,
SMS provider credentials, internal lease fields, database IDs unrelated to the
event, or arbitrary entity properties.

### Resolver behavior

- Parse and validate placeholders when a template is saved.
- Show a variable picker based on a sample event in the console.
- Resolve only dotted map paths; do not execute Aviator, JavaScript, SQL, or
  template functions.
- Reject empty paths, collection iteration, includes, partials, and arbitrary
  method calls in the first version.
- Treat missing values as a render error for required placeholders. Do not
  silently send a message containing raw `{{...}}` text.
- Provide a preview that clearly marks missing values before a template can be
  activated.
- Add default values and conditional sections only as a later, explicitly
  designed feature. Do not invent a second expression language inside templates.

### Escaping rules

The same variable must be escaped according to the output channel:

- Email subject: header-safe text with line breaks removed and length bounded.
- Email plain text: plain text with normalized line endings.
- Email HTML: HTML-escape inserted values by default. A data field must never
  become raw HTML merely because the template is an HTML document.
- SMS: plain text only; reject or strip HTML tags and normalize whitespace.

Provide an explicit safe helper for formatting dates, numbers, and currency
later. Do not allow raw/unescaped variables in the first release.

### Compile and render lifecycle

1. Admin writes a template and selects a channel.
2. Backend parses placeholders and rejects unsupported syntax.
3. Backend validates channel-specific fields and size limits.
4. Admin previews the template with a sample event.
5. An immutable template version is activated.
6. At event processing time, Tengen resolves the current event/rule context,
   renders the message, and stores the rendered snapshot in the outbox.
7. Retries use the stored snapshot, not a newly edited template.

If rendering fails during event processing, still keep the event and rule match.
Create a terminal notification record with `TEMPLATE_RENDER_ERROR` and do not
call the provider. This makes the failure visible without turning a valid event
into a failed ingestion request.

## Email action system

### Email provider strategy

Use an adapter interface rather than encoding SMTP fields into a rule:

```text
EmailProvider
  test(destination)
  submit(message, idempotencyKey)
  optional delivery-event handling
```

Possible adapters:

- `SMTP`: useful for self-hosted installations and local controlled relays;
- `EMAIL_API`: transactional provider API with request IDs, rate-limit signals,
  bounces, complaints, and delivery webhooks.

Recommended first delivery target: implement the provider interface and one
adapter selected by deployment needs. SMTP is the broadest self-hosted fallback;
an API provider is preferable when delivery status and bounce handling matter.
Do not commit the roadmap to a provider until its credentials, regional
requirements, and delivery-event API are chosen.

### Email configuration

Destination configuration should support:

- connection type and provider;
- host/port/TLS mode for SMTP or endpoint/region for an API provider;
- encrypted username/password or API key reference;
- verified from address and display name;
- optional reply-to address;
- allowed recipient count and domain policy;
- connection-test status;
- request timeout, concurrency, rate, and retry policy; and
- optional provider delivery-event signing configuration.

Rule notification configuration should support:

- one or more fixed `To` addresses for the first version;
- optional fixed `CC` only if privacy and display semantics are explicit;
- a template version;
- an optional rule-specific sender/reply-to override constrained by the
  destination; and
- a preview/test recipient separate from production recipients.

Avoid BCC in the first version unless the UI makes recipient privacy explicit.
Never put recipients in a URL query string or log line.

### Email message format

Every email should be sent as `multipart/alternative`:

1. Required plain-text part.
2. Optional HTML part.

Recommended message fields:

- From from the managed destination;
- To from the rule configuration;
- Reply-To only when allowed by destination policy;
- Subject from the template;
- deterministic Tengen notification ID for tracing;
- provider-specific idempotency key where available; and
- no arbitrary user-supplied headers.

Attachments, inline file uploads, and arbitrary MIME parts are out of scope for
the first email slice. Links to approved HTTPS resources can be considered later.

### HTML and CSS support

Yes, email can support styling, but it must be email-safe CSS rather than a
general web page.

Recommended v1 email styling contract:

- Allow an HTML body template plus an optional CSS block.
- Run the template through a real HTML sanitizer and CSS inliner before queueing
  or sending; do not use regular expressions to sanitize HTML.
- HTML-escape dynamic variables before inserting them into the HTML tree.
- Restrict tags and attributes to a documented allowlist.
- Remove scripts, event-handler attributes, forms, iframes, embeds, `javascript:`
  URLs, remote stylesheets, and unsafe URLs.
- Support a conservative CSS property allowlist such as color, background-color,
  font-family, font-size, font-weight, line-height, text-align, padding,
  margin, border, width, and simple layout properties.
- Prefer email-compatible table/box layouts. Do not promise full browser CSS,
  flexbox, grid, animations, or custom fonts.
- Inline ordinary selectors into element `style` attributes. Preserve only
  explicitly supported media-query behavior if the chosen inliner can sanitize
  it safely; otherwise warn and remove it.
- Provide a plain-text fallback so messages remain readable when HTML is
  stripped or unsupported.
- Show warnings for unsupported CSS rather than silently suggesting that it will
  render consistently in every mail client.

Store the authored template and the compiled/sanitized result separately or
store enough versioned metadata to reproduce the result. Queued messages must
retain the compiled snapshot used for the send.

### Email delivery semantics

Classify provider results conservatively:

- transient network, timeout, provider 429, and provider 5xx: retry;
- invalid credentials, invalid sender, malformed address, blocked domain, and
  provider permanent rejection: fail or dead-letter without hot-looping;
- accepted by SMTP/provider: mark `SUBMITTED`;
- provider delivery webhook confirms inbox acceptance: mark `DELIVERED`;
- bounce/complaint/unsubscribe event: mark the attempt with the provider event
  and apply the configured recipient policy.

SMTP acceptance is not the same as inbox delivery. The UI and API must use
`SUBMITTED` when there is no provider receipt rather than claiming delivery.

### Email deliverability and security prerequisites

Document deployment requirements outside the application:

- SPF, DKIM, and DMARC alignment for the sender domain;
- verified sender/domain at the chosen provider;
- TLS certificate validation and secret rotation;
- provider sending limits and regional restrictions; and
- bounce/complaint handling if an API provider is used.

Protect against:

- header injection through subject, display name, or reply-to;
- HTML/CSS/script injection through templates or event data;
- recipient enumeration in logs or admin list views;
- leaking event PII into provider diagnostics; and
- unrestricted external image/link fetching by the backend.

### Email console and API

Add:

- `EMAIL` to the action selector;
- email destination create/list/update/test APIs;
- template create/list/version/preview/activate APIs;
- email-specific rule configuration fields;
- subject, text, HTML, CSS, and sample-event preview;
- a test-send endpoint requiring an explicit confirmation and a verified
  recipient; and
- email notification list/detail/attempt history with safe recipient masking.

`Run Test` must render an email preview without sending it. Test send is a
separate explicit operation, must not update cooldown/EDGE/once-per-window
state, and must be audited.

### Email acceptance criteria

- An admin can configure an email destination without exposing its secret.
- A rule using `EMAIL` can render `{{data.first_name}}` and send a multipart
  text/HTML message asynchronously.
- Dynamic values are escaped correctly in subject, text, and HTML output.
- CSS is sanitized, inlined or rejected with a clear warning, and cannot execute
  script or make the backend fetch an arbitrary resource.
- Email provider failure retries safely and appears in notification history.
- SMTP acceptance is shown as `SUBMITTED` unless delivery confirmation exists.
- Existing LOG and WEBHOOK rules and producer responses remain unchanged.

## SMS action system

### SMS provider strategy

Use a provider adapter interface:

```text
SmsProvider
  test(destination)
  submit(message, idempotencyKey)
  optional delivery-receipt handling
```

Do not implement several providers in the first slice. Choose one provider
according to deployment country, sender requirements, cost, and delivery-event
support, then keep the adapter boundary so another provider can be added later.

### SMS configuration

Destination configuration should support:

- provider and region;
- encrypted API credential or secret-manager reference;
- sender number, short code, or sender ID;
- allowed destination countries;
- maximum message segments;
- per-minute and daily spend/message budgets;
- provider timeout/retry policy;
- connection-test result; and
- delivery-receipt callback verification if supported.

Rule notification configuration should support:

- one or more fixed E.164 phone numbers initially;
- a template version;
- a test number separate from production recipients; and
- an explicit transactional notification category.

Event-derived phone numbers should be a later option with a controlled field
path such as `data.phone_number`; never allow arbitrary template text to become
a phone number.

### SMS rendering and segment rules

SMS is plain text. It does not support CSS, HTML layout, images, or rich content.

Before queueing:

- resolve `{{data.first_name}}` and other approved variables;
- HTML-escape/strip markup so `<b>` cannot be sent as accidental content;
- normalize line endings and whitespace;
- preserve intentional Unicode characters;
- calculate GSM-7 versus UCS-2 encoding and segment count;
- reject messages beyond the configured segment limit; and
- show the estimated character/segment count in the template preview.

Do not silently truncate an SMS. The admin must shorten the template or raise a
documented limit.

The template renderer should store the normalized rendered text and encoding
classification in the outbox so retries use the same segment calculation.

### Consent and opt-out

The first SMS implementation must be transactional-alert-only and must define:

- how a recipient is marked opted in or opted out;
- how STOP/unsubscribe provider events are ingested;
- whether an opted-out recipient blocks the whole notification or only that
  recipient;
- who can override an opt-out, if anyone; and
- how consent/opt-out changes are audited and retained.

If the product cannot establish a compliant consent/opt-out path for the target
country/provider, keep SMS disabled for that deployment. Do not treat a phone
number in an event or rule as consent.

### SMS delivery semantics

Classify results:

- timeout, connection failure, provider 429, and provider 5xx: retry;
- invalid phone number, unsupported country, blocked sender, invalid
  credentials, and policy rejection: permanent failure;
- provider accepted message: `SUBMITTED`;
- provider receipt says sent/delivered: update the notification status;
- undeliverable/failed receipt: record the provider reason and do not hot-loop.

Use a stable Tengen notification ID and provider idempotency key where the
provider supports one. The provider message ID must be stored for support and
receipt correlation.

### SMS console and API

Add:

- `SMS` to the action selector;
- SMS destination create/list/update/test APIs;
- SMS template create/list/version/preview/activate APIs;
- recipient masking and consent status display;
- rendered character/segment preview;
- a test-send endpoint requiring explicit confirmation and a verified number;
- per-destination budget/rate status; and
- notification/attempt/receipt history with safe provider messages.

`Run Test` renders only. Test send must be separate, audited, rate-limited, and
excluded from rule trigger/cooldown state.

### SMS acceptance criteria

- An admin can configure an SMS provider without exposing credentials.
- A rule using `SMS` can render a plain-text message with
  `{{data.first_name}}` and queue it asynchronously.
- Invalid phone numbers, opt-out recipients, unsupported countries, and
  over-limit messages are rejected before provider submission.
- Segment count and provider status are visible in notification history.
- Provider failures retry or dead-letter without blocking event ingestion or
  changing unrelated rule state.
- SMS remains disabled until a valid provider test and consent policy are in
  place.

## Rule and API changes

### Backend

Plan the following additive changes:

- Extend `RuleAction` with `EMAIL` and `SMS`.
- Add notification destination/template/config DTOs and repositories.
- Extend rule validation so `EMAIL`/`SMS` require a matching destination and
  active template, while `WEBHOOK` keeps callback URL validation.
- Reuse current trigger restrictions:
  - `EVERY_MATCH` for all supported notification rules;
  - `EDGE` where current rule semantics support it;
  - `ONCE_PER_WINDOW` only for aggregate rules; and
  - absence/sequence restrictions remain unchanged.
- Extract common trigger reservation and notification intent logic from the
  webhook-specific branch without changing webhook behavior.
- Add `NotificationOutboxService`, channel workers, provider adapters, and safe
  status/attempt DTOs.
- Extend event outcome/detail models with an additive notification channel and
  notification ID. Preserve current `LOG_ONLY`, `WEBHOOK_QUEUED`, and
  `WEBHOOK_SUPPRESSED` values for compatibility.
- Add configuration limits for template size, rendered message size, email
  recipients, SMS segments, provider timeout, retry attempts, and worker batch.

### Frontend

Update [`RuleForm`](../frontend/src/components/RuleForm.tsx) without changing
the single-action interaction:

- Action selector becomes `LOG`, `WEBHOOK`, `EMAIL`, `SMS`.
- EMAIL shows destination, template, recipients, subject/body preview, and
  optional HTML/CSS editor.
- SMS shows destination, template, recipients, character/segment preview, and
  consent warning.
- Add a variable picker and sample-event selector for templates.
- Add clear warnings that preview does not send anything.
- Add explicit test-send confirmation separate from Run Test.
- Add loading, provider error, rate-limit, opt-out, and template-render states.

Add pages or panels for:

- Email destinations;
- SMS destinations;
- versioned notification templates;
- notification history and attempts; and
- provider/consent status.

## Delivery state and idempotency

Use one logical deduplication key per rule revision, event, channel, and trigger
scope:

```text
EMAIL:rule={ruleId}:revision={revision}:event={eventId}
SMS:rule={ruleId}:revision={revision}:event={eventId}
```

For EDGE, cooldown, once-per-window, aggregate group, sequence, and absence
rules, include the existing scope/window/instance components. If the template or
destination changes, a new rule revision should create a new logical key; an
existing queued row retains its old snapshot.

Retries must:

- reuse the same notification ID and deduplication key;
- not create another event or rule match;
- not create another cooldown reservation;
- use the same rendered message and recipient snapshot; and
- record the attempt/provider ID separately.

Manual retry should be limited to permanent operational failures where resending
is safe. A changed template should not rewrite an old notification. A later
operator feature may allow explicit re-render/requeue from the original event,
but that is outside the first slice.

## Security, privacy, and abuse controls

- Encrypt SMTP passwords, email API keys, and SMS API keys at rest.
- Support secret rotation without invalidating in-flight outbox snapshots.
- Never expose provider credentials, raw phone numbers, or unmasked recipients
  in normal list APIs.
- Redact recipients and message content from logs and metric labels.
- Enforce template size, body size, recipient count, rate, concurrency, daily
  budget, and retry limits.
- Apply separate SSRF/network policies to webhook delivery and provider
  destinations. Email/SMS providers should use an explicit adapter/endpoint
  allowlist rather than arbitrary URLs from a rule.
- Sanitize HTML/CSS using a trusted parser/inliner and escape all dynamic values.
- Reject email header injection and invalid display names.
- Validate E.164 numbers and country policy before queueing SMS.
- Audit template activation, destination changes, test sends, manual retries,
  consent changes, and provider credential rotation.
- Keep test sends separate from production action state and require an explicit
  confirmation step.
- Add deployment guidance for email domain authentication and SMS provider
  compliance; application code cannot guarantee deliverability or legal
  consent by itself.

## Implementation phases

### Phase 0 — Contract and provider decisions

Priority: P0 · planning only

Decide:

- first email transport: SMTP, one provider API, or both;
- first SMS provider and supported countries;
- fixed recipients only versus controlled event-field recipients;
- template syntax and missing-variable behavior;
- email CSS allowlist and sanitizer/inliner library;
- template version/activation workflow;
- notification status vocabulary (`SUBMITTED` versus `DELIVERED`);
- retry, budget, rate, and retention defaults; and
- secret-manager versus encrypted database credentials.

Exit criteria:

- Provider contracts and sample messages are documented.
- A template security/data policy is approved.
- A Flyway/data migration plan preserves existing webhook rows and rule
  revisions.

### Phase 1 — Common notification foundation

Priority: P0 · effort: L–XL

- Add notification destinations, immutable template versions, rule notification
  configuration, notification outbox, and optional attempt history.
- Add strict template parse/compile/render support for common variables.
- Add channel-specific escaping and size validation interfaces.
- Add worker lease/retry/dead-letter behavior based on the existing webhook
  worker patterns.
- Add destination test and template preview endpoints with no production action.
- Keep current webhook storage/worker path unchanged except for additive outcome
  and administration support.

Acceptance criteria:

- A notification template can be parsed, previewed, activated, and rendered
  against a sample event without sending.
- A rendered notification is stored immutably in a durable outbox row.
- A worker crash after claiming work leaves the row recoverable.

### Phase 2 — Email action

Priority: P0 · effort: L

- Extend single-action rule validation and UI with `EMAIL`.
- Implement the selected email provider adapter.
- Implement multipart text/HTML delivery with sanitized CSS support.
- Add provider result classification, `SUBMITTED` state, optional delivery
  event ingestion, and safe email history.
- Add a feature flag/configuration guard so no email is sent until a destination
  has passed its test.

Acceptance criteria: all email acceptance criteria above pass and existing
webhook/log behavior remains unchanged.

### Phase 3 — SMS action

Priority: P1 · effort: L

- Extend rule validation and UI with `SMS`.
- Implement one selected SMS provider adapter.
- Implement E.164/segment/consent/budget validation.
- Add provider receipt handling if available.
- Add feature flag/configuration guard and notification history.

Acceptance criteria: all SMS acceptance criteria above pass and SMS failures do
not affect other event processing.

### Phase 4 — Hardening and operations

Priority: P0/P1 · effort: M–L

- Add notification dashboard metrics for queued, submitted, delivered, failed,
  dead-lettered, provider-throttled, and template-failed states.
- Add manual retry with permissions and audit.
- Add retention for notification outbox/attempts while preserving audit records
  and required consent history.
- Add provider health and budget alerts.
- Add operational runbooks for provider outage, credential rotation, bounce,
  opt-out, and stuck lease recovery.
- Update README, HTML documentation, LLM reference, API examples, and template
  authoring guidance.

## Compatibility and rollout

1. Add `EMAIL` and `SMS` as additive action values; existing rules remain
   `LOG`/`WEBHOOK`.
2. Add new tables and nullable rule/config references without changing existing
   webhook columns or outbox rows.
3. Deploy the notification foundation with workers disabled.
4. Create and test a destination and template using preview only.
5. Enable email for a controlled test rule and verified recipients.
6. Observe provider submissions, retries, and render failures before widening
   the rollout.
7. Enable SMS only after the provider, supported countries, consent/opt-out,
   budget, and receipt behavior are verified.
8. Keep old webhook delivery history and APIs working during the entire rollout.

## Verification plan

### Template tests

- Parse valid placeholders and reject malformed braces, unsupported expressions,
  method calls, loops, includes, and raw variable modes.
- Resolve nested paths such as `data.customer.first_name`.
- Mark missing values and ensure raw `{{...}}` text is never sent.
- Verify subject/header, plain-text, HTML, and SMS escaping independently.
- Verify template versions render deterministically after later edits.
- Verify HTML sanitizer removes scripts, event handlers, unsafe URLs, forms,
  iframes, remote stylesheets, and disallowed CSS.
- Verify CSS inlining produces a valid fallback message and warnings for
  unsupported properties.
- Verify SMS GSM-7/UCS-2 classification, Unicode behavior, line endings, and
  segment limits.

### Email tests

- Use fake SMTP/provider clients; never send to a real mailbox in automated
  tests.
- Verify multipart text/HTML construction and dynamic-value escaping.
- Verify header-injection rejection and sender/reply-to policy.
- Verify provider 429/5xx/timeouts retry and permanent provider errors fail
  without hot loops.
- Verify successful SMTP/API submission is shown as `SUBMITTED`.
- Verify optional provider delivery/bounce events update the same notification.
- Verify duplicate event processing does not create a second email intent.
- Verify test-send does not change rule cooldown, EDGE, once-per-window, or
  absence state.

### SMS tests

- Use a fake SMS provider with controllable accepted, throttled, failed, and
  receipt responses.
- Verify phone normalization/E.164 validation and unsupported-country rejection.
- Verify opted-out recipients are blocked and changes are audited.
- Verify segment limits and budget/rate limits are enforced before submission.
- Verify provider idempotency/message IDs and receipt correlation.
- Verify retries/dead letters do not affect event acceptance or webhook state.

### Backend and frontend verification

- Verify old `LOG` and `WEBHOOK` rules still validate, evaluate, and deliver as
  before.
- Verify email/SMS template/destination revisions are immutable for queued work.
- Verify event responses distinguish matched, suppressed, queued, and render
  failed notification outcomes without exposing message content.
- Verify the console masks credentials, recipients, and phone numbers.
- Verify migration/backfill with existing rule revisions and webhook outbox
  rows.
- Add Playwright coverage for destination setup, template preview, rule setup,
  test-send confirmation, notification history, and provider failure states.

Per the existing repository instructions, do not create or run new Spring Boot
integration tests until the user explicitly approves them. This plan defines
the tests but does not run them.

## Explicit non-goals for this plan

- Multiple actions per rule or action fan-out.
- Multiple RabbitMQ queue consumption or outbound queue publishing.
- Chat, incident, ticket, or native SaaS integrations.
- Marketing campaigns, bulk email, or bulk SMS.
- Arbitrary template code, JavaScript, SQL, Aviator expressions, or scripts.
- Attachments, inline file uploads, or arbitrary email MIME parts.
- Dynamic recipients before their privacy, validation, and consent policies are
  designed.
- Guaranteed inbox delivery or exactly-once external provider effects.

## Decisions before implementation

1. Which email transport should be the first supported adapter: SMTP or a
   transactional provider API?
2. Which SMS provider and countries are required?
3. Should fixed recipients support multiple addresses/numbers in v1, and what
   are the maximums?
4. Which event fields may be exposed to templates beyond `data.*`?
5. Should a missing template variable create a terminal `FAILED` record or be
   blocked at rule activation based on a declared variable contract?
6. Which CSS sanitizer/inliner is acceptable for the Java 21/Spring Boot stack?
7. Will email delivery receipts/bounces be in the first adapter or a follow-up?
8. What daily/email/SMS rate and spend limits are safe for each deployment?

The recommended next step is Phase 0 followed by the common notification
foundation and email action. SMS should follow after the provider and consent
policy are explicit.
