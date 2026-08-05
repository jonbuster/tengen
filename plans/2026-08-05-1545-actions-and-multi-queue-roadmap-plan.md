# Tengen Actions and Multi-Queue Integration Roadmap

## Status: Proposed — 2026-08-05 15:45 PHT

This is a planning-only document. It does not implement any of the features
described here.

## Short answer

Yes, Tengen can consume multiple RabbitMQ queues, but the current MVP cannot do
that safely yet. The current implementation is intentionally built around one
saved connector, one queue, and one dynamic listener. Supporting multiple queues
requires a queue-binding model, one independently managed listener per queue,
clear ordering/ownership rules, and queue-level monitoring.

For actions, the recommended v2 direction is not to keep adding enum values such
as `EMAIL`, `SMS`, `SLACK`, and `PAGERDUTY` directly to the current single-action
rule model. Instead:

```text
event match
    -> action bindings selected by the rule
        -> durable action intents
            -> channel/provider workers
                -> delivery attempts, retries, and outcomes
```

This allows one rule to log, send a webhook, email an operator, and publish to a
queue at the same time, while each action has its own destination, template,
retry policy, rate limit, and delivery state.

## Current-state findings

The current code provides a good foundation but has deliberately narrow
boundaries:

- [`RuleAction`](../tengen/src/main/java/com/tengencorp/tengen/entity/RuleAction.java)
  contains only `LOG` and `WEBHOOK`.
- [`Rule`](../tengen/src/main/java/com/tengencorp/tengen/entity/Rule.java) has
  one `callbackUrl`, one trigger configuration, and one action per rule.
- [`EventService`](../tengen/src/main/java/com/tengencorp/tengen/service/EventService.java)
  evaluates a match and either records `LOG_ONLY` or enqueues one webhook.
- [`WebhookOutboxService`](../tengen/src/main/java/com/tengencorp/tengen/service/WebhookOutboxService.java)
  and [`WebhookDeliveryWorker`](../tengen/src/main/java/com/tengencorp/tengen/service/WebhookDeliveryWorker.java)
  already provide durable asynchronous delivery, retries, leases, and
  dead-lettering that can be generalized.
- [`RabbitMqConnectorService`](../tengen/src/main/java/com/tengencorp/tengen/service/RabbitMqConnectorService.java)
  uses `findFirstByOrderByIdAsc()` and treats the first saved row as the only
  connector.
- [`RabbitMqRuntimeManager`](../tengen/src/main/java/com/tengencorp/tengen/service/RabbitMqRuntimeManager.java)
  owns one dynamic listener rather than a runtime map of listeners.
- The existing RabbitMQ MVP plan explicitly assumes one connector/queue and one
  backend replica while enabled. Its receipt identity and manual-ack processing
  are useful starting points for a multi-queue design.

## Product model to target

### A rule match is not an action

Keep rule evaluation responsible for answering:

```text
Did this event match this rule, and what was the evaluation result?
```

Move action selection and delivery into a separate layer:

```text
Which actions are enabled for this match?
What destination and template does each action use?
What trigger/cooldown policy applies to each action?
What is the durable delivery state for each action?
```

`LOG` remains an internal outcome and does not need an external delivery row.
Every external action should be asynchronous and committed as a durable intent
with the event and rule outcome. The event-ingestion transaction must never wait
for SMTP, an SMS provider, Slack, PagerDuty, or another external system.

### One rule can fan out to multiple actions

Recommended v2 behavior:

- A rule has zero or more action bindings.
- A match creates at most one logical action intent per eligible binding.
- One failed action does not block the other actions for the same match.
- Each action binding can have its own destination, template, cooldown,
  trigger mode, retry policy, and enabled state.
- Existing v1 `LOG` rules remain equivalent to a rule with no external action.
- Existing v1 `WEBHOOK` rules are migrated to one HTTP webhook action binding.
- Existing webhook outbox rows remain deliverable using their immutable URL and
  payload snapshots; migration must not duplicate them.

Per-action trigger state is important. An email may be suppressed for ten
minutes while a webhook is sent on every eligible match. Reusing one rule-level
`pendingOutboxId` for all channels would incorrectly couple their delivery
lifecycles.

## Action catalog

### Recommended action types

| Action | How it connects | Best use | Initial priority | Important constraints |
| --- | --- | --- | --- | --- |
| `LOG` | Internal event trace and audit record | Always-on explainability and local diagnostics | Existing | Not an external delivery; should not be treated as a retryable action. |
| `HTTP_WEBHOOK` | HTTPS endpoint with signing and retries | Generic integrations and custom automation | Existing / generalize | SSRF protection, secret rotation, idempotency, response classification, and receiver-side deduplication. |
| `EMAIL` | SMTP or transactional email provider API | Human-readable alerts, summaries, approval requests | P0 candidate | Provider credentials, sender/domain verification, recipient validation, template safety, bounce handling, and privacy. |
| `SMS` | SMS provider API | Urgent, short, mobile alerts | P1 candidate | E.164 phone numbers, consent/opt-out, country support, segmentation, cost/rate limits, and delivery receipts. |
| `CHAT` | Slack/Teams/Discord app or incoming webhook | Team notifications and rich operational context | P1 candidate | Provider-specific formatting, channel authorization, message updates, and rate limits. |
| `INCIDENT` | PagerDuty/Opsgenie/incident API | Open, deduplicate, acknowledge, and resolve incidents | P1 candidate | Requires stable incident/dedup keys and lifecycle actions, not only one-way sends. |
| `TICKET` | Jira/Linear/GitHub Issues or service-desk API | Create/update an investigation or work item | P2 candidate | Needs correlation and update semantics to avoid creating one ticket per event. |
| `QUEUE_PUBLISH` | RabbitMQ exchange/queue, Kafka topic, SQS, or another broker | Republish normalized events or rule outcomes to downstream systems | P1/P2 candidate | Publish confirms, routing, schema/versioning, ordering, and at-least-once semantics. |
| `DATABASE_WRITE` | A tightly controlled internal sink | Integrations that need a durable projection | Defer | Arbitrary SQL or user-provided connection strings are unsafe; use named managed sinks only. |
| `METRIC` | Internal counter/gauge or metrics event | Operational aggregation | Defer / internal | Prefer emitting bounded metrics from the platform rather than making users configure arbitrary metric labels. |

### Actions not recommended for the first implementation

- Arbitrary scripts, shell commands, or user-supplied functions. They create a
  code-execution and secret-exfiltration boundary.
- Generic database writes with arbitrary SQL or credentials.
- Sending marketing email/SMS. v2 should focus on transactional alerts and
  document consent, opt-out, and retention requirements.
- Pretending that an external provider gives exactly-once effects. Tengen can
  provide durable at-least-once intent and provider idempotency where available;
  the receiver/provider remains part of the guarantee boundary.

## Target data model

The exact names can change during implementation, but the separation should be
preserved.

### `action_destinations`

One workspace-scoped connection target or provider configuration:

- stable ID and display name;
- destination type (`HTTP`, `SMTP`, `EMAIL_PROVIDER`, `SMS_PROVIDER`, `SLACK`,
  `PAGERDUTY`, `RABBITMQ_PUBLISH`, and so on);
- provider-specific non-secret configuration;
- encrypted credentials or secret references;
- secret/key version and rotation state;
- enabled/disabled state and last successful connection test;
- provider limits such as requests per second or daily budget; and
- created/updated/last-used metadata.

Never return credentials to the browser after creation. Keep the current
RabbitMQ AES-256-GCM pattern as a starting point, but prefer a deployment secret
manager for production credentials.

### `rule_actions`

One rule-to-action binding:

- rule ID and immutable rule revision;
- action type;
- destination ID, nullable for `LOG`;
- template ID/version or inline configuration snapshot;
- enabled state and display name;
- trigger mode and cooldown override;
- ordering or parallel execution preference;
- per-action retry and timeout policy; and
- a stable action key used for idempotency and audit.

The current single `action`, `callbackUrl`, `triggerMode`, and
`cooldownSeconds` fields should remain readable for v1 compatibility during a
migration period. New v2 rules should use action bindings. Do not make the new
model depend on a nullable column for every future provider.

### `action_templates`

Versioned, validated templates for messages and payloads:

- channel/type and template version;
- subject/title/body fields appropriate to the channel;
- plain-text and optional HTML email variants;
- variable references such as `event.data.orderId`, with an allowlisted path
  resolver;
- maximum rendered size and a test/sample payload;
- active/archived state; and
- creator and audit metadata.

Templates must render as text or structured JSON. Do not evaluate arbitrary
code, HTML, SQL, or Aviator expressions inside a template.

### `action_outbox` and `action_attempts`

Generalize the webhook outbox without making existing webhook history
unreadable:

- Keep `webhook_outbox` as a compatibility table or migrate it into a generic
  outbox with a safe, tested backfill.
- Store immutable action type, destination snapshot/reference, template
  snapshot, rendered payload or message, event/rule/revision IDs, scope,
  deduplication key, and trigger metadata.
- Track `PENDING`, `PROCESSING`, `RETRY_SCHEDULED`, `DELIVERED`, `SUPPRESSED`,
  `FAILED`, and `DEAD_LETTER` at the action level.
- Store attempt timestamp, duration, provider/message ID, status/category,
  bounded error, and safe response metadata.
- Use one deduplication key per rule revision, event/match, action binding, and
  trigger scope. A retry must update the same logical intent rather than insert
  another action.
- Record action outcome in Event Explorer and expose a delivery timeline for
  every external channel.

An email failure must not mark the webhook as failed, and a dead-lettered SMS
must not prevent a queue publish from succeeding.

## RabbitMQ: consuming multiple queues

### Is it possible?

Yes. There are three normal deployment shapes:

1. One RabbitMQ broker, multiple queues: one broker connection can host multiple
   channels and consumers. This is the common case.
2. Multiple brokers or virtual hosts: use one connection profile per broker or
   virtual host and attach one or more queue bindings to it.
3. Multiple queues with strict ordering: use one active consumer per queue,
   prefetch `1`, and an explicit single-active-consumer/lease policy.

The existing implementation is not a limitation of RabbitMQ itself; it is an
MVP data-model and runtime-manager limitation.

### Recommended model: connection profiles plus queue bindings

Do not store `queueA,queueB,queueC` in one text field. Model the boundaries as:

```text
RabbitMQ connection profile
    ├── queue binding: orders.in
    ├── queue binding: payments.in
    └── queue binding: fraud.in
```

Use two related concepts:

#### `rabbitmq_connections`

Shared broker connection configuration:

- workspace ID;
- stable connection key and display name;
- host, port, virtual host, TLS settings, username;
- encrypted password or secret-manager reference;
- allowed-host policy and connection-test state; and
- connection-level health and lifecycle metadata.

#### `rabbitmq_queue_bindings`

One independently controllable consumer:

- connection ID and stable binding key;
- queue name;
- dead-letter exchange and routing key;
- selected Tengen API key or ingestion policy;
- enabled desired state;
- ordering mode (`ORDERED` or explicit `PARALLEL`);
- consumer count and prefetch limits, bounded by deployment policy;
- retry/backoff/dead-letter settings;
- schema/event contract reference if schema validation is enabled; and
- configuration version/test state.

This supports many queues on one broker and queues on different brokers without
forcing them to share retry, API-key, or failure behavior.

### Runtime design

The runtime manager should own a map keyed by binding ID:

```text
binding 101 -> listener container -> orders.in
binding 102 -> listener container -> payments.in
binding 103 -> listener container -> fraud.in
```

Recommended behavior:

- Create one listener container per queue binding. This isolates connection,
  retry, pause, and error state for each queue.
- Reuse a broker connection/factory for bindings sharing the same host, port,
  and virtual host when safe, but use separate channels/consumer containers.
- Use manual acknowledgement and acknowledge only after the PostgreSQL
  transaction commits.
- Keep `ORDERED` as the default: one active consumer and prefetch `1`.
- Make `PARALLEL` an explicit opt-in with a clear warning that event order and
  some sequence semantics are no longer guaranteed.
- Set a bounded global listener/thread limit so adding queues cannot create an
  unbounded number of threads or connections.
- Start, stop, test, and rebuild one binding without interrupting healthy
  bindings.
- On one binding's authentication, queue, or dead-letter failure, pause that
  binding and keep other bindings running.
- Reconcile desired state on application startup and close every listener,
  channel, and connection on shutdown or configuration replacement.

### Receipt and processing identity

Keep the current durable receipt approach, but scope it to the queue binding:

```text
(workspace_id, queue_binding_id, queue_name, message_id)
```

The message ID must be supplied by the producer and remain the durable broker
identity. Do not use delivery tags or a payload hash.

Every queue binding should call the same authorized core event processor used by
HTTP and the current RabbitMQ MVP. The queue, exchange, routing key, and
binding configuration must not become the event's `type` or `source`; those
values remain in the JSON contract.

### Multi-replica ownership choices

Multiple queues are easy in one backend process. Multiple backend replicas need
an ownership decision:

| Option | Ordering | Operational shape | Recommendation |
| --- | --- | --- | --- |
| RabbitMQ Single Active Consumer per queue | Strong per-queue ordering | Broker elects one active consumer; standby consumers can exist | Recommended for ordered queues if the broker/deployment supports it. |
| Durable database lease per queue binding | Strong if only one lease holder consumes | Tengen owns leader election and stale-lease recovery | Good fallback when Single Active Consumer is unavailable. |
| Competing consumers | No strict ordering | Highest throughput and simplest scaling | Explicit opt-in only; not compatible with all sequence use cases. |

Do not silently move from one ordered consumer to competing consumers when a
second replica is deployed. The ordering mode must be part of the connector
contract, health state, and documentation.

## Channel plans

### Phase 0 — Action and connector contracts

Priority: P0 · dependency for every new action and multiple queues

Decide and document:

- whether v2 rules support multiple action bindings (recommended: yes);
- whether trigger/cooldown is shared at rule level or overridden per action
  (recommended: per-action override with a rule-level default);
- whether actions run in parallel or ordered sequence (recommended: parallel
  with independent failure state; provide ordering only where a channel needs
  it);
- provider idempotency and Tengen deduplication guarantees;
- static versus event-derived recipients/destinations;
- transactional alerts versus marketing/consent workflows;
- how secrets are stored and rotated; and
- maximum action count, template size, recipient count, queue count, and worker
  concurrency per workspace.

Exit criteria:

- An action contract defines intent, deduplication, state transitions, retry,
  dead-letter, suppression, audit, and privacy behavior.
- A queue-binding contract defines ordering, ownership, ack timing, receipt
  identity, and failure isolation.

### Phase 1 — Generalized action outbox and destinations

Priority: P0 · effort: XL

Implement the platform layer before adding email or SMS:

- Support multiple action bindings while mapping v1 `LOG` and `WEBHOOK` rules
  without changing existing producer responses.
- Add destination records with encrypted configuration, safe test operations,
  secret rotation, enabled state, and workspace ownership.
- Extract common action-intent creation from `EventService` and
  `AbsenceEvaluationWorker`.
- Add channel-neutral action outbox/attempt state and per-action suppression.
- Preserve immutable destination/template/payload snapshots for queued work.
- Extend Event Explorer, delivery history, compact/full responses, and audit
  records additively.
- Keep webhook delivery as the first adapter, using the current SSRF validation,
  signatures, retries, leases, and manual retry behavior.

Acceptance criteria:

- One matched event can queue two independent webhook actions without duplicate
  intents.
- Retrying or dead-lettering one action does not alter another action's state.
- Existing webhook deliveries continue after migration and remain traceable.

### Phase 2 — Multiple RabbitMQ queue consumption

Priority: P0/P1 · effort: L–XL · can proceed in parallel with Phase 1 after
the core processor boundary is stable

Implement the connection-profile/queue-binding model described above:

- Migrate the existing single connector to one connection profile and one queue
  binding with the same message receipt behavior.
- Add list/create/update/test/enable/disable APIs for bindings and connection
  profiles. Editing one binding must not stop unrelated bindings.
- Replace the single-listener runtime manager with a binding-keyed manager.
- Add queue-level status, message rate, retry count, dead-letter count, oldest
  unprocessed age, last error, and active consumer information.
- Add an ordered default and an explicit parallel mode.
- Add multi-replica ownership using Single Active Consumer or a durable lease;
  choose one and test it before exposing more than one backend replica.
- Keep API-key authorization, schema validation, receipt deduplication, and
  event processing shared across HTTP and all queues.

Acceptance criteria:

- Two queues on the same broker can be enabled simultaneously and process
  messages independently.
- A failure in one queue's connection or dead-letter route does not pause the
  others.
- A redelivery on any queue creates no second Tengen event when its receipt is
  retained.
- Ordered queues preserve the documented order under restart and one active
  consumer; parallel mode documents its weaker ordering.
- Two backend replicas do not create two active ordered consumers for one
  binding.

### Phase 3 — Email action

Priority: P0 · effort: L

Start with transactional email alerts rather than a marketing campaign system.

Connection options:

- `SMTP`: broad self-hosted compatibility, TLS modes, username/password or
  secret-manager reference, sender identity, and connection test.
- `EMAIL_PROVIDER_API`: provider adapter with API credential, sender/domain,
  provider request ID, and provider status.

Recommended first release: define a provider interface and implement one
deployment-friendly adapter, with SMTP available when self-hosting requires it.
Do not hard-code provider-specific fields into the rule table.

Email scope:

- managed email destination with from/reply-to and optional fixed recipients;
- versioned subject, plain-text, and optional sanitized HTML templates;
- allowlisted event/rule variables and rendered-message preview;
- recipient count and rendered-size limits;
- provider idempotency key where supported;
- retry classification for connection errors, throttling, and provider 5xx;
- permanent failure handling for invalid addresses and rejected sender domains;
- safe storage of provider message IDs and bounded response details; and
- optional bounce/complaint status ingestion as a later follow-up.

Do not permit arbitrary recipient expressions in the first slice. Add dynamic
recipients only after field extraction, PII controls, recipient validation, and
audit behavior are defined.

Acceptance criteria:

- A rule can send an email without blocking event ingestion.
- Email retries and dead letters are visible separately from webhook deliveries.
- A rendered email cannot execute arbitrary template code or leak credentials.
- Duplicate event delivery produces at most one logical email intent per action
  deduplication key.

### Phase 4 — SMS action

Priority: P1 · effort: L

Treat SMS as a regulated, cost-bearing provider channel, not as a shortened
email.

Scope:

- provider adapter with encrypted API credentials and connection test;
- sender number, short code, or sender ID configuration where supported;
- strict E.164 phone-number validation;
- fixed recipients first, dynamic recipients later;
- text-only versioned templates with rendered-length and segment limits;
- per-workspace/per-destination rate and spending budgets;
- consent and opt-out state for transactional notifications;
- provider idempotency key and provider message ID storage;
- retry only transient provider/network failures; and
- delivery receipt support when the provider exposes it.

Acceptance criteria:

- An SMS action is rejected before queueing when the destination or message is
  invalid.
- The system cannot send to an opted-out recipient.
- Segment count, provider status, and cost/budget signals are visible to an
  operator without storing unnecessary message content.
- A provider outage dead-letters or retries SMS only; it does not block other
  actions for the same rule match.

### Phase 5 — Chat and incident actions

Priority: P1 · effort: M–L per provider

Build the highest-value team integrations on the same destination/template
abstraction:

- `CHAT`: Slack, Microsoft Teams, or Discord with provider-specific payload
  templates and destination tests. Start with one provider selected by demand.
- `INCIDENT`: PagerDuty, Opsgenie, or similar with event key/dedup key,
  severity, source, component, open/acknowledge/resolve behavior, and a
  documented mapping from rule lifecycle to incident lifecycle.
- Add rate limiting and provider-specific retry/error classification.
- Allow message/update correlation without creating a new incident or ticket
  for every matching event.

These are more useful than simply adding more generic webhooks because the
platform can expose provider-specific health, deduplication, and lifecycle
semantics while still using the common action worker.

### Phase 6 — Queue publishing and downstream actions

Priority: P1/P2 · effort: L per broker

Separate outbound publishing from inbound consumption, even when both use
RabbitMQ:

- Add `QUEUE_PUBLISH` destinations with broker connection, exchange/topic,
  routing key, serialization/schema version, and publish-confirm policy.
- Publish the rule outcome or a selected normalized event envelope, not an
  arbitrary database object.
- Persist a publish intent before sending and use provider confirms before
  marking it delivered.
- Document ordering, duplicate, and replay behavior independently from inbound
  queue consumption.
- Add Kafka/SQS/etc. only after a concrete customer requirement and a broker-
  specific offset/ownership/dead-letter design.

Do not reuse inbound queue bindings as an implicit outbound action target. A
system may consume from `orders.in` and publish to `orders.alerts`, but those are
different managed resources with different credentials and policies.

### Phase 7 — Operations, templates, and action UX

Priority: P0/P1 · runs across all channel phases

Admin console:

- Action builder with Add action, action type, destination, template, trigger,
  cooldown, and enable/disable controls.
- Destination pages with safe configuration forms, test action, last-used time,
  secret rotation, provider status, and masked credentials.
- Template editor with sample event preview, variable allowlist, rendered
  output, validation, version history, and archive.
- Action delivery timeline linked from Event Explorer: queued, suppressed,
  attempted, delivered, retried, and dead-lettered.
- Connector page showing broker profiles and queue bindings as a list rather
  than one singleton form; each binding has its own status and controls.
- Bulk retry only for authorized operators, with selection limits and an audit
  record.

Metrics and alerts:

- action intents by type/status;
- delivery latency and retry/dead-letter rate by bounded destination/type;
- provider throttling and circuit-breaker state;
- email/SMS budget and segment usage;
- RabbitMQ binding consumer status, queue lag, redelivery count, and dead-letter
  rate; and
- oldest pending action/queue message age.

Never put recipient addresses, phone numbers, message bodies, credentials, or
raw provider responses into metric labels.

## Compatibility and migration plan

1. Add the new action/destination tables and queue-binding tables without
   removing existing columns.
2. Backfill each existing `WEBHOOK` rule into one HTTP action binding while
   preserving the callback URL and signing behavior.
3. Keep existing `webhook_outbox` rows readable and deliverable until their
   retention window expires; do not copy them into a second outbox during
   migration.
4. Backfill the current RabbitMQ row into one connection profile and one queue
   binding with the same connector ID/receipt relationship where possible.
5. Keep old rule API fields accepted for v1 clients. Return additive action
   binding data in v2 responses; do not silently change the old `action` field.
6. Roll out new action workers disabled, verify schema and migration state, then
   enable one channel at a time.
7. Do not enable multi-replica ordered consumers until ownership behavior has
   been tested and the deployment contract is documented.

## Verification plan

### Action platform

- Multiple action bindings create one intent per eligible binding and no
  duplicates on idempotent event replay.
- Per-action cooldown, EDGE, and once-per-window policies do not interfere with
  one another.
- A failed/dead-lettered email or SMS leaves webhook and queue-publish state
  unchanged.
- Destination changes do not rewrite immutable queued intent snapshots.
- Secret rotation supports in-flight work and does not expose old/new secrets.
- Templates reject unsafe variables, oversized output, and malformed provider
  payloads.
- Existing webhook delivery behavior and producer responses remain compatible.

### Email and SMS adapters

- Use fake SMTP/provider clients for unit and contract tests; never call a real
  provider from automated tests.
- Verify retryable/permanent provider status classification, idempotency keys,
  provider IDs, timeouts, rate limits, and dead-letter behavior.
- Verify invalid addresses/phone numbers and opt-out recipients are rejected
  before intent creation.
- Verify provider outage isolation and safe error messages.

### Multiple RabbitMQ queues

- Create at least two queues on one broker and consume them concurrently.
- Restart one listener and prove the other queue continues processing.
- Verify one receipt/event per unique `(binding, queue, message_id)` and safe
  redelivery after a crash between database commit and broker ack.
- Verify ordered mode with prefetch `1` and one active consumer.
- Verify explicit parallel mode documents and demonstrates weaker ordering.
- Verify multiple backend replicas do not double-consume an ordered binding.
- Verify one queue's dead-letter failure pauses only that binding.
- Verify connection replacement closes all old channels/containers.

Per the repository's existing plan instructions, do not create or run new Spring
Boot integration tests until the user explicitly approves them. The plan only
defines the required verification; it does not run it now.

## Decisions before implementation

1. Which email connection is required first: SMTP, a provider API, or both?
2. Which SMS provider and target countries are in scope?
3. Are actions configured once per rule or can one rule fan out to multiple
   actions? Recommendation: multiple action bindings.
4. Are recipients fixed destinations initially, with event-derived recipients
   deferred? Recommendation: fixed first.
5. Which chat/incident provider has the strongest initial demand?
6. Should ordered RabbitMQ queues use RabbitMQ Single Active Consumer or a
   Tengen database lease across replicas?
7. What are the maximum queues, action bindings, recipients, provider calls,
   message size, and delivery backlog per workspace?
8. Is outbound queue publishing required in the same release as inbound
   multi-queue consumption, or should it follow as a separate action adapter?

## Recommended implementation order

1. Approve the action and queue-binding contracts.
2. Generalize the durable action/outbox model while preserving current webhook
   behavior.
3. Split RabbitMQ broker profiles from queue bindings and add independent
   listener lifecycle/ownership.
4. Add email with one provider adapter and transactional templates.
5. Add SMS with consent, budget, and provider receipt handling.
6. Add the highest-value chat or incident adapter.
7. Add outbound queue publishing only if a concrete downstream use case exists.
8. Finish the operations dashboard, audit, rate limits, and failure testing for
   every enabled action and queue binding.
