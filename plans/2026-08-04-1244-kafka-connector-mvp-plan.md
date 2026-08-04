# Kafka Connector MVP Plan

## Status: Planned - 2026-08-04 12:44 PHT

## Recommendation

Add one optional, configuration-driven Kafka consumer that accepts the existing
`EventRequest` JSON shape and commits a broker offset only after Tengen's event,
rule outcomes, and eligible webhook intents commit to PostgreSQL.

Use a durable broker-message receipt keyed by connector, topic, partition, and
offset. This gives the connector at-least-once transport with effectively
once-only Tengen processing across redelivery, without claiming distributed
exactly-once delivery.

Keep the connector disabled by default and preserve the HTTP ingestion contract.

## Dependency and Ordering

This is the second roadmap slice after the
[replay/backfill MVP](2026-08-04-1244-replay-backfill-job-mvp-plan.md), but it
does not depend on replay tables or replay evaluation state. It does depend on a
small extraction of the authorized core event-processing transaction from the
HTTP-specific idempotency and response-projection wrapper.

## Current-State Findings

- `POST /api/events` is the only production ingestion transport.
- `EventService` currently combines API-key authorization, HTTP idempotency,
  event-time classification, persistence, rule evaluation, trace creation,
  webhook intent creation, and response projection.
- HTTP idempotency is scoped to an API-key ID plus caller-supplied key.
- API keys already enforce active/expiry state and allowed event types/sources.
- PostgreSQL transactions already commit an accepted event and its webhook
  intent atomically.
- Watermarks serialize events per source/type and classify old records before
  rule evaluation.
- No Kafka dependency, connector configuration, broker receipt table, or broker
  test fixture exists.

## Goals

- Consume the existing event JSON contract from one configured topic.
- Preserve the exact rule, watermark, event trace, and webhook behavior used by
  HTTP ingestion.
- Associate broker-ingested events with a dedicated existing Tengen API key so
  source/type permissions and revocation still apply.
- Deduplicate redelivery independently of API-key rotation.
- Acknowledge Kafka records only after the PostgreSQL transaction commits.
- Retry transient processing failures without skipping the record.
- Route malformed or permanently invalid records to a configured dead-letter
  topic before acknowledging the original record.
- Preserve partition ordering.
- Expose safe connector health and metrics without logging event bodies or
  credentials.
- Show ingestion origin and safe Kafka coordinates in Event Explorer.

## Non-Goals

- Do not replace or change `POST /api/events`.
- Do not support multiple connector definitions, topic patterns, or multiple
  clusters in the MVP.
- Do not add RabbitMQ, SQS, Pulsar, or a generic plugin framework.
- Do not add a connector management UI or store broker credentials in Tengen's
  database.
- Do not provide outbound event publishing.
- Do not use Kafka transactions or claim end-to-end exactly-once semantics.
- Do not replay historical Kafka offsets through an admin UI.
- Do not deserialize Avro, Protobuf, CloudEvents, or schema-registry payloads.
- Do not persist Kafka message keys or arbitrary headers.
- Do not apply the HTTP in-memory rate limiter to broker consumption; broker
  concurrency and backpressure are the ingestion controls.

## Message Contract

The Kafka record value uses the same JSON shape accepted by the HTTP API:

```json
{
  "type": "payment",
  "source": "billing",
  "timestamp": "2026-08-04T04:30:00Z",
  "data": {
    "amount": 2500,
    "orderId": "order-123"
  }
}
```

- `type`, `source`, and `data` remain required.
- `timestamp` remains optional and falls back to processing time, matching HTTP
  behavior.
- Apply Jakarta validation explicitly after deserialization because the message
  does not pass through an MVC `@Valid` boundary.
- Apply the existing maximum future-skew rule.
- Enforce the configured API key's active, expiry, event-type, and source scope.
- Do not derive event type/source from topic names or headers in the MVP.

## Delivery and Deduplication Contract

Identify a broker record by:

```text
(connector_id, topic, partition, offset)
```

Processing order:

1. Receive one record under manual acknowledgement.
2. Deserialize and validate the value.
3. Start the PostgreSQL transaction.
4. Reserve the unique broker receipt.
5. If a completed receipt already exists, treat the record as a redelivery and
   skip event processing.
6. Resolve and authorize the configured Tengen API key.
7. Run the same authorized core processing used by HTTP ingestion.
8. Link the receipt to the persisted event and commit PostgreSQL.
9. Acknowledge the Kafka record so its offset can advance.

If the process stops after step 8 and before step 9, Kafka may redeliver the
record. The durable receipt makes that redelivery a no-op before it is
acknowledged. Receipt, event, watermark change, outcomes, rule state, and outbox
intent must share the same PostgreSQL transaction.

Do not use HTTP `event_idempotency` for broker coordinates. Its API-key scope and
stored producer response are transport-specific and would make deduplication
depend on retaining one credential forever.

## Failure Classification

Classify failures without including payload values in logs or metrics.

### Retriable infrastructure failures

Examples include PostgreSQL unavailability, transaction serialization failure,
temporary broker failure, and unexpected internal exceptions.

- Retry with bounded exponential backoff.
- Do not acknowledge the original record while retrying.
- Preserve partition ordering by not advancing past the failed record.
- After configured exhaustion, publish to the dead-letter topic; acknowledge the
  original only after the dead-letter publish succeeds.

### Permanent message failures

Examples include malformed JSON, bean-validation failure, excessive future
timestamp, or API-key scope rejection for that event.

- Do not call the core processor.
- Publish the original value to the dead-letter topic with bounded safe headers:
  connector ID, original topic/partition/offset, failure category, and failure
  time.
- Never add raw credentials, stack traces, rule expressions, or callback URLs to
  dead-letter headers.
- Acknowledge the original only after dead-letter publication succeeds.

### Connector credential/configuration failures

An absent, invalid, revoked, or expired configured API key is a connector-wide
operational failure, not a poison message.

- Do not send every record to the dead-letter topic.
- Stop or pause listener consumption before records are processed.
- Mark connector readiness unhealthy and expose a fixed failure reason.
- Resume only after configuration is valid and the application/listener is
  restarted in the MVP.

Rule expression errors keep existing Tengen semantics: they are recorded by
normal rule evaluation as non-matches and do not fail or dead-letter the broker
record.

## Data Model

Use the next available Flyway migration. If the replay plan is implemented
first, this is expected to be `V11__kafka_ingestion.sql`.

### `kafka_message_receipts`

Persist:

- `id`;
- bounded `connector_id`;
- topic, partition, and offset;
- API-key ID used for authorization;
- nullable event ID with `ON DELETE SET NULL` so normal event retention does not
  remove the deduplication receipt;
- processed timestamp; and
- a unique constraint on `(connector_id, topic, partition, offset)`.

A committed row means processing completed. Do not persist a `PROCESSING` row in
a separate transaction; an unsuccessful processing attempt must roll back its
reservation automatically.

Add lookup indexes for event ID and processed time. Apply the existing terminal
operational retention window to old receipts only after it is longer than the
maximum expected Kafka replay/redelivery horizon, and document that tradeoff.

### Event ingestion metadata

Add additive event metadata:

- `ingestion_origin` with values `HTTP` and `KAFKA`, backfilling existing rows to
  `HTTP` and making it non-null;
- nullable `connector_id` for Kafka-origin events.

Topic, partition, and offset remain in the receipt table and are exposed through
an event-detail lookup. Do not place arbitrary broker metadata in the event JSON
payload.

## Backend Refactor

Extract a package-internal authorized event processor from `EventService`:

- Input: validated `EventRequest`, resolved `ApiKey`, and a fixed ingestion
  context (`HTTP` or `KAFKA`, with safe connector metadata).
- Output: persisted event identity plus the full internal `EventResponse` needed
  by HTTP projection.
- Responsibility: future-skew check, API-key scope validation, watermark
  classification, event persistence, rule evaluation, trace creation, trigger
  state, and durable webhook intent.

Keep these responsibilities outside the core processor:

- HTTP filter authentication and rate limiting;
- HTTP idempotency reservation/replay;
- `FULL` versus `COMPACT` producer response projection; and
- Kafka receipt reservation, retry, dead-lettering, and offset acknowledgement.

The HTTP controller and response contract must remain byte-compatible for
existing keys. Add regression tests before moving code so the refactor cannot
silently change ingestion behavior.

## Kafka Consumer Design

- Add the Boot-managed Kafka dependency without pinning a conflicting version.
- Bind validated settings under `tengen.kafka.*`.
- Create the listener only when `tengen.kafka.enabled=true`.
- Use byte/string input followed by explicit JSON deserialization so malformed
  values can be classified and dead-lettered predictably.
- Use manual record acknowledgement and one-record processing.
- Default listener concurrency to `1`; allow bounded concurrency across
  partitions while preserving ordering inside each partition.
- Validate connector configuration and the dedicated API key before starting
  consumption.
- Use a separate service bean for the transactional receipt plus core-processing
  call so Spring transaction interception occurs before acknowledgement.
- Use the configured dead-letter topic; default its name to
  `<input-topic>.tengen-dlt` when omitted.
- Ensure a dead-letter record retains the original value but logs only its byte
  length and safe coordinates.

The listener must not invoke the local HTTP endpoint. It calls the shared core
processor directly so database work remains one transaction and no raw API key
is placed in internal HTTP traffic.

## Configuration

Add settings for:

- enabled state, connector ID, bootstrap servers, input topic, group ID;
- raw Tengen API key supplied only through secret environment configuration;
- client security properties using standard Spring Kafka configuration;
- listener concurrency and maximum record size;
- retry attempts, initial delay, multiplier, and maximum delay; and
- dead-letter topic.

Keep connector operation disabled by default. Never print the raw API key,
bootstrap credentials, or full security-property map.

Add an optional Compose profile for local Kafka-compatible infrastructure and
document that it is not started by the default application stack. Production
operators may point the connector at an external cluster instead.

## Event Explorer and Admin UI

Extend event history summaries/details with:

- ingestion origin;
- connector ID for Kafka events; and
- topic, partition, and offset on the event detail page when the receipt still
  exists.

Add an origin filter (`HTTP`, `KAFKA`) to Event Explorer. Do not expose broker
addresses, consumer credentials, raw headers, or a connector settings form.

Dead-letter messages remain in Kafka in this MVP. Provide documentation and
metrics, not a Tengen dead-letter browser.

## Health, Metrics, and Logging

- Add a readiness contributor that is healthy when the connector is disabled or
  configured and consuming normally, and unhealthy for connector-wide auth or
  configuration failures.
- Count accepted, deduplicated, retried, and dead-lettered records.
- Gauge listener running state and consumer lag when safely available from the
  client metrics without unbounded labels.
- Limit metric labels to connector ID and fixed result/category values; do not
  use topic dynamically if only one configured topic exists.
- Log safe coordinates, event ID after success, attempt, duration, and fixed
  failure category.
- Never log record values, API keys, broker credentials, rule expressions,
  callback URLs, or arbitrary Kafka headers.

## Retention

Kafka receipts protect deduplication only while retained. Reuse the global
operational retention worker with an explicit documented rule:

- delete receipts only when `processed_at` is older than the configured
  retention cutoff; and
- warn operators that resetting a consumer farther back than the receipt
  retention horizon can process an old record again.

Delete event rows independently using `ON DELETE SET NULL` on the receipt.

## Implementation Sequence

1. Add ingestion-origin metadata and durable Kafka receipt schema.
2. Extract and regression-test the authorized core event processor.
3. Add connector properties, conditional configuration, and startup credential
   validation.
4. Add transactional receipt reservation and Kafka listener processing.
5. Add retry/dead-letter classification and manual acknowledgement.
6. Add Event Explorer origin/coordinate API and UI changes.
7. Add health, metrics, retention, Compose profile, and documentation.
8. Run focused tests, full backend tests, frontend lint/tests/build, and the
   broker integration suite.

## Verification Plan

### Unit tests

- Existing HTTP ingestion and response projection remain unchanged after the
  core processor extraction.
- Kafka JSON mapping and Jakarta validation match the HTTP request contract.
- API-key active, expiry, type, and source checks are enforced.
- Failure classification selects retry, dead-letter, or connector pause without
  exposing sensitive values.
- Receipt identity uses connector/topic/partition/offset exactly.

### PostgreSQL and Kafka integration tests

- A valid broker record creates one event, normal rule outcomes, and at most one
  webhook intent before its offset is acknowledged.
- Redelivering the same coordinates returns through the receipt without creating
  a second event or mutating rule/watermark state.
- A simulated crash after database commit and before acknowledgement is safe on
  redelivery.
- A database failure leaves the record unacknowledged and retries it.
- Malformed and permanently invalid records reach the dead-letter topic; the
  original offset advances only after that publish succeeds.
- A dead-letter publish failure leaves the original record unacknowledged.
- Records remain ordered inside one partition and may process concurrently
  across partitions when configured.
- Revoked/expired connector credentials stop consumption rather than filling the
  dead-letter topic.
- A `TOO_LATE` Kafka event is retained once and creates no rule/action state,
  matching HTTP semantics.

Use isolated PostgreSQL and Kafka test containers. Keep local development and
production brokers untouched by automated tests.

### Frontend tests

- Event origin filtering sends the correct query.
- HTTP and Kafka origin chips render correctly.
- Kafka coordinates appear only when present.
- Existing Event Explorer filters and event detail links remain stable.

## Acceptance Criteria

The feature is complete when an operator can enable one Kafka connector, publish
an existing Tengen event JSON value, observe exactly one accepted Tengen event
with normal rule/webhook behavior, force a redelivery without duplicate
processing, inspect its safe broker coordinates in Event Explorer, and route a
malformed value to the dead-letter topic without advancing the original offset
prematurely. Disabling the connector must leave the current HTTP-only deployment
unchanged.

## Risks and Mitigations

- **Database commit succeeds but Kafka acknowledgement is lost.** Use a durable
  receipt and treat redelivery as success without reprocessing.
- **Offset commits before durable processing.** Require manual acknowledgement
  only after the transactional service returns successfully.
- **API-key rotation breaks deduplication.** Scope receipts to connector/topic/
  partition/offset rather than API-key ID.
- **One bad record blocks a partition forever.** Apply bounded retries, then
  dead-letter before acknowledging.
- **A bad connector credential dead-letters healthy traffic.** Validate the
  connector credential separately and stop the listener on connector-wide auth
  failure.
- **Refactoring changes HTTP semantics.** Add full/compact, idempotency,
  watermark, outcome, and outbox regression coverage before enabling Kafka.
- **Sensitive payloads leak through diagnostics.** Use fixed error categories,
  safe coordinates, and explicit negative log tests.
- **Old offset reset exceeds receipt retention.** Document the deduplication
  horizon and keep receipts at least as long as operational replay requirements.

## Follow-Up

After this slice, implement
[replay job controls and history](2026-08-04-1244-replay-job-controls-history-plan.md).
Additional broker types should be planned from a concrete integration target,
not by generalizing the Kafka MVP prematurely.
