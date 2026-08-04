# RabbitMQ Header-Gated Watermark Opt-Out Plan

## Status: Implemented - 2026-08-04 21:39 PHT

## Decision

Use the AMQP header `x-tengen-watermark` as an opt-out signal for RabbitMQ
events:

- Header absent: apply the existing watermark classification and row lock.
- Header `x-tengen-watermark: true`: apply the existing watermark behavior.
- Header `x-tengen-watermark: false`: skip watermark creation, lookup, locking,
  and advancement for that event.

This is backward-compatible for existing producers because the safe default is
to keep watermark processing enabled. Only an explicit boolean false disables
it.

Treat only the boolean value `false` and the case-insensitive string `"false"`
as opt-out. Missing, malformed, or other values default to watermark enabled so
an accidental header value cannot silently bypass event-time protection.

The header is a processing hint, not an authorization control. API-key active,
expiry, and event-type/source scope validation remain unchanged.

## Why This Is Needed

Watermark processing currently runs for every event and locks the watermark row
for its `(event type, source)` stream. That lock is useful for important
event-time semantics, but it serializes events sharing one hot stream. The
recent load test demonstrated substantially higher throughput when events used
multiple sources, confirming that same-stream watermark contention is a major
constraint.

Some producers may classify low-value or informational events as not important
enough to participate in event-time ordering. Those events can be persisted and
evaluated without creating or updating a watermark row.

## Scope

### RabbitMQ ingestion

Read the header from Spring AMQP `MessageProperties.getHeaders()` in the
RabbitMQ delivery path. Pass a resolved `applyWatermark` flag into the shared
event-processing service without changing the HTTP ingestion contract.

Keep the existing RabbitMQ body validation, receipt reservation, API-key
validation, event persistence, rule evaluation, acknowledgement, retry, and
dead-letter behavior.

### Event-time persistence

For `applyWatermark=true`, preserve the current behavior exactly:

1. Create or lock the `(event type, source)` watermark row.
2. Classify the event as `ON_TIME`, `LATE_ACCEPTED`, or `TOO_LATE`.
3. Persist the classification and watermark decision timestamp.
4. Skip rule evaluation for `TOO_LATE` events as today.

For `applyWatermark=false`:

1. Do not call the watermark service.
2. Do not create, lock, update, or advance a watermark row.
3. Persist the event and continue normal rule evaluation.
4. Store no event-time classification or watermark decision timestamp.

Because nullable event-time fields currently also represent legacy rows, add a
small persisted `watermark_applied` marker for new events if the Event Explorer
needs to distinguish intentional opt-out from historical/legacy null values.
Existing rows should remain compatible; do not persist the raw AMQP header.

### Metrics and observability

Add counters for watermark-applied and watermark-skipped RabbitMQ events, or
extend the existing RabbitMQ processing metrics with a safe result tag. Do not
log message bodies, arbitrary headers, credentials, or API-key values.

## API and Rule Semantics

An unwatermarked event is still an accepted event and still participates in
rule evaluation. This means an old event with an explicit opt-out can trigger a
rule even though a watermarked event with the same timestamp might have been
classified as `TOO_LATE`. This behavior must be documented and covered by
tests.

Unwatermarked events do not advance the stream watermark. A later watermarked
event is classified only against watermark progress made by other watermarked
events.

Do not remove API-key scope validation as part of this feature. Watermark
selection and ingestion authorization are separate concerns.

## Implementation Steps

1. Add a small header parser with fail-safe defaults and support for Spring
   AMQP boolean, string, and byte-array header representations.
2. Resolve the header in `RabbitMqMessageProcessingService` and pass the flag
   to `EventService.processRabbitMq`.
3. Add a watermark-aware processing branch in `EventService` that skips only
   watermark work when the flag is false; keep HTTP processing unchanged.
4. Prevent null event-time decisions from reaching event-time metric switches
   or `TOO_LATE`-specific logic.
5. Add the `watermark_applied` persistence marker if product/API consumers need
   to distinguish skipped watermarking from legacy rows, including a Flyway
   migration and DTO/history mapping.
6. Add a Python load-test option such as `RABBITMQ_WATERMARK=false` or a
   source/header mode so marked and unmarked event throughput can be compared.
7. Update the RabbitMQ message contract documentation with the header behavior
   and the event-time tradeoff.

## Verification

### Automated tests

- Missing header applies the watermark.
- Header `true` applies the watermark.
- Header `false` skips watermark creation and locking.
- Malformed header values default to applying the watermark.
- Unwatermarked events still persist and run rules.
- HTTP ingestion behavior remains unchanged.
- Watermarked `TOO_LATE` behavior remains unchanged.
- API-key scope validation still rejects unauthorized messages.

### Manual performance tests

Run the same persistent AMQP publisher under these controlled cases:

1. Same source, header absent: compatibility baseline.
2. Same source, header `false`: measure the benefit when the hot-stream lock is
   removed.
3. Multiple sources, header absent: measure parallel watermark streams.
4. Mixed important/unimportant messages: measure the expected production mix.

For every case, record publisher confirmations, Tengen accepted-event rate,
queue ready depth, unacknowledged count, database CPU/connection usage, and
dead-letter count. Confirm the queue drains after publishing stops.

## Risks and Rollout

- Producers can omit the header accidentally and will receive the safe,
  watermark-enabled behavior.
- Producers can explicitly opt out and change late-event/rule semantics; the
  header should therefore be documented and enabled only for trusted producers.
- Skipping the watermark does not remove event, receipt, API-key, or rule
  database work, so it may not eliminate all throughput limits.
- Keep the feature disabled as an opt-out only; do not make `false` the global
  default without a separate product decision.
