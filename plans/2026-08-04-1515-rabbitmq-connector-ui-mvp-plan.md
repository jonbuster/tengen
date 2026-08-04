# RabbitMQ Connector with Admin UI MVP Plan

## Status: Planned - 2026-08-04 15:15 PHT

## Supersedes

This plan replaces the Kafka connector MVP plan. The MVP now targets one
RabbitMQ queue and includes UI-based connection configuration. Kafka and other
broker types are outside this slice.

## Recommendation

Add one optional RabbitMQ consumer that accepts the existing `EventRequest` JSON
shape and acknowledges a delivery only after Tengen's event, rule outcomes, and
eligible webhook intents commit to PostgreSQL.

Add a dedicated **Connectors > RabbitMQ** admin page where an administrator can:

- enter the RabbitMQ host, port, virtual host, username, password, TLS mode, and
  existing queue name;
- select an existing Tengen API key as the connector's ingestion policy;
- enter the dead-letter exchange and routing key;
- save a disabled draft, test the connection, enable consumption, and disable
  consumption; and
- see safe runtime status and the last connection-test result.

The browser does not connect to RabbitMQ. It sends the configuration to Tengen's
JWT-protected backend API. The Spring Boot backend decrypts the saved password,
opens the AMQP connection, and runs the queue consumer.

Store the RabbitMQ password encrypted with an application master key supplied
only through deployment configuration. Never return the password to the UI or
store it as plaintext. Keep HTTP ingestion unchanged and keep the RabbitMQ
connector disabled until an administrator explicitly enables it.

## What UI Configuration Means

The intended runtime flow is:

1. An administrator opens the RabbitMQ connector page.
2. The administrator enters the connection and queue settings.
3. The frontend sends those settings to the Spring Boot admin API over the
   existing authenticated proxy.
4. The backend validates the fields and encrypts the password before storing it
   in PostgreSQL.
5. **Test connection** makes a temporary backend-to-RabbitMQ connection, checks
   authentication and virtual-host access, and passively verifies that the
   configured queue and dead-letter exchange exist.
6. **Enable** starts a backend RabbitMQ listener using the tested configuration.
7. RabbitMQ pushes queue messages to the backend; the frontend is not involved
   in message delivery.
8. **Disable** stops the listener and closes its connection without deleting the
   saved configuration.
9. After an application restart, an enabled and still-valid connector starts
   automatically.

The backend container or host must be able to resolve and reach the RabbitMQ
server. For example, `localhost` entered in the UI refers to the backend's own
network namespace, which may be a Docker container rather than the user's Mac.

## Dependency and Ordering

This remains the next roadmap slice after the
[replay/backfill MVP](2026-08-04-1244-replay-backfill-job-mvp-plan.md). It does
not depend on replay tables or replay evaluation state.

It does depend on extracting the authorized core event-processing transaction
from the HTTP-specific idempotency and response-projection wrapper. The UI and
runtime connector also require a new secure connector configuration model; the
existing `admin_settings` singleton must remain limited to visual preferences.

## Current-State Findings

- `POST /api/events` is the only production ingestion transport.
- The current Settings page stores only theme, accent-color, and time-display
  preferences.
- No RabbitMQ dependency, AMQP connection factory, listener, connector API,
  encrypted-secret storage, broker receipt table, or RabbitMQ test fixture
  exists.
- `EventService` currently combines API-key authorization, HTTP idempotency,
  event-time classification, persistence, rule evaluation, trace creation,
  webhook intent creation, and response projection.
- API keys already enforce active/expiry state and allowed event types/sources.
- PostgreSQL transactions already commit an accepted event and its webhook
  intent atomically.
- Watermarks serialize events per source/type and classify old records before
  rule evaluation.

## Goals

- Configure the actual RabbitMQ connection and queue from the admin UI.
- Test, enable, disable, and monitor one connector without restarting Tengen.
- Consume the existing event JSON contract from one existing RabbitMQ queue.
- Preserve the exact rule, watermark, event trace, and webhook behavior used by
  HTTP ingestion.
- Associate broker-ingested events with a selected existing Tengen API key so
  source/type permissions and revocation still apply.
- Acknowledge RabbitMQ deliveries only after the PostgreSQL transaction commits.
- Deduplicate redelivery independently of API-key rotation.
- Retry transient processing failures without consuming the next queued message.
- Publish malformed or permanently invalid messages to a configured dead-letter
  exchange before acknowledging the original delivery.
- Preserve queue order in the supported single-consumer deployment.
- Expose safe connector health and metrics without logging message bodies or
  credentials.
- Show HTTP or RabbitMQ origin and safe AMQP metadata in Event Explorer.

## Non-Goals

- Do not replace or change `POST /api/events`.
- Do not add Kafka, SQS, Pulsar, or a generic connector framework.
- Do not support multiple RabbitMQ connectors or queues in the MVP.
- Do not create, delete, bind, or mutate RabbitMQ queues, exchanges, users, or
  permissions from Tengen. The input queue and dead-letter exchange must already
  exist.
- Do not require or integrate the RabbitMQ Management HTTP API.
- Do not connect to RabbitMQ directly from browser JavaScript.
- Do not support custom TLS certificate upload through the UI; use the backend
  JVM trust store for private certificate authorities.
- Do not support OAuth, client-certificate authentication, or secret-manager
  integrations in the MVP.
- Do not provide outbound business-event publishing.
- Do not claim distributed exactly-once delivery.
- Do not deserialize Avro, Protobuf, or CloudEvents.
- Do not persist arbitrary AMQP headers.
- Do not apply the HTTP in-memory rate limiter to broker consumption; listener
  prefetch and concurrency provide backpressure.
- Do not guarantee queue ordering when multiple Tengen backend replicas or other
  consumers share the same queue.

## RabbitMQ and Deployment Preconditions

- The queue is durable, pre-created, and dedicated to Tengen.
- The RabbitMQ user can connect to the configured virtual host, read from the
  input queue, publish to the dead-letter exchange, and passively inspect the
  named queue/exchange for connection testing.
- The backend network can reach the configured host and port.
- Producers publish persistent JSON messages and assign a unique AMQP
  `message_id` to every logical event.
- The MVP runs one Tengen backend replica while the connector is enabled.
- Listener concurrency and prefetch both default to `1` to preserve processing
  order and bound unacknowledged work.

Multi-replica ownership, competing consumers, and higher concurrency require a
separate ordering and leadership design.

## Message Contract

The RabbitMQ message body uses the same JSON shape accepted by the HTTP API:

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

AMQP requirements:

- AMQP `message_id` (`messageId` in Spring AMQP) is required, unique for each
  logical message within the queue, and at most 255 characters.
- `content_type` should be `application/json`; missing content type may be
  accepted, but a conflicting content type is permanently invalid.
- The body must be UTF-8 JSON and must not exceed the configured ingestion byte
  limit.
- `type`, `source`, and `data` remain required.
- `timestamp` remains optional and falls back to processing time, matching HTTP
  behavior.
- Apply Jakarta validation explicitly after deserialization because the message
  does not pass through an MVC `@Valid` boundary.
- Apply the existing maximum future-skew rule.
- Enforce the selected API key's active, expiry, event-type, and source scope.
- Do not derive event type or source from the queue, exchange, routing key, or
  headers.

Do not use the RabbitMQ delivery tag as a durable identifier. Delivery tags are
scoped to a channel and can change after reconnection or redelivery. Do not fall
back to a payload hash because two legitimate events may have identical JSON.
A missing `message_id` is a permanent message failure.

## Delivery and Deduplication Contract

Identify a broker message by:

```text
(connector_id, queue_name, message_id)
```

Processing order:

1. Receive one message with manual acknowledgement and prefetch `1`.
2. Check body size, required AMQP properties, JSON parsing, and Jakarta
   validation.
3. Start the PostgreSQL transaction.
4. Reserve the unique RabbitMQ receipt.
5. If a completed receipt already exists, treat the message as a redelivery and
   skip event processing.
6. Resolve and authorize the connector's selected API key.
7. Run the same authorized core processing used by HTTP ingestion.
8. Link the receipt to the persisted event and commit PostgreSQL.
9. Send `basic.ack` for only that delivery on the same RabbitMQ channel.

If the process stops after step 8 and before step 9, RabbitMQ may redeliver the
message. The durable receipt makes that redelivery a no-op before it is
acknowledged. Receipt, event, watermark change, outcomes, rule state, and webhook
intent must share the same PostgreSQL transaction.

Do not use HTTP `event_idempotency` for RabbitMQ identities. Its API-key scope
and stored producer response are transport-specific.

This provides at-least-once transport with effectively once-only Tengen event
processing while receipts are retained and publishers honor unique
`message_id` values. It is not end-to-end exactly-once delivery.

## Failure Classification

Classify failures without including body values or credentials in logs or
metrics.

### Retriable infrastructure failures

Examples include PostgreSQL unavailability, transaction serialization failure,
temporary RabbitMQ channel failure, and unexpected internal exceptions.

- Retry in the listener with bounded exponential backoff while the delivery
  remains unacknowledged.
- Do not repeatedly requeue without delay because that creates a hot redelivery
  loop.
- Keep prefetch at `1` so the next message does not overtake the failed message.
- After configured exhaustion, publish the original body to the dead-letter
  exchange using publisher confirms, mandatory routing, and publisher returns.
- Acknowledge the original only after the dead-letter publish is confirmed and
  was not returned as unroutable.
- If dead-letter publication fails, leave the original unacknowledged, pause the
  connector, and expose a fixed operational error in the UI.

### Permanent message failures

Examples include missing `message_id`, malformed JSON, bean-validation failure,
excessive future timestamp, body-size violation, conflicting content type, or
API-key scope rejection for that event.

- Do not call the core processor.
- Publish the original body to the dead-letter exchange with bounded Tengen
  headers: connector ID, source queue, failure category, and failure time.
- Preserve only the original `message_id` and `content_type`; do not copy
  arbitrary headers.
- Never add raw credentials, stack traces, rule expressions, callback URLs, or
  full exception messages to dead-letter headers.
- Acknowledge the original only after confirmed and successfully routed
  dead-letter publication.

Dead-letter delivery is at least once. A process failure after dead-letter
publication but before acknowledgement can produce a duplicate dead-letter
message; consumers of the dead-letter queue should use `message_id` for
deduplication.

### Connector-wide failures

Examples include invalid connection settings, authentication failure, missing
queue, missing dead-letter exchange, revoked/expired selected API key, missing
encryption master key, or an undecryptable saved password.

- Do not dead-letter every queued message.
- Do not start, or pause, the listener before additional records are consumed.
- Keep the admin UI and HTTP ingestion available so an administrator can repair
  the connector.
- Expose a fixed safe category such as `AUTHENTICATION_FAILED`, `QUEUE_MISSING`,
  or `API_KEY_REVOKED`.
- Require a successful test of the current configuration version before it can
  be enabled again.

Rule expression errors keep existing Tengen semantics: normal rule evaluation
records them as non-matches and does not fail or dead-letter the broker message.

## Secure UI Configuration

### Admin fields

The connector form contains:

- display name;
- host and port (`5672` without TLS or `5671` with TLS as suggested defaults);
- virtual host;
- TLS enabled;
- username;
- password, shown as an empty replace-only field after save;
- existing input queue name;
- existing dead-letter exchange and routing key;
- selected active Tengen API-key ID;
- maximum body bytes;
- retry attempts, initial delay, multiplier, and maximum delay; and
- enabled state controlled by explicit Enable and Disable actions.

Keep prefetch and listener concurrency fixed at `1` in the MVP instead of
presenting unsafe tuning controls.

### Secret storage

- Add `TENGEN_CONNECTOR_MASTER_KEY`, containing a Base64-encoded 32-byte random
  key, to deployment configuration.
- Require the master key before a RabbitMQ password can be saved or an existing
  connector can be started. The rest of Tengen may run without it when no
  connector is configured.
- Encrypt the password with AES-256-GCM using a new random nonce for every write.
- Store ciphertext, nonce, and key version; never store or log plaintext.
- Never return ciphertext, nonce, or plaintext through an API. Return only
  `passwordConfigured: true|false`.
- A blank password on update means retain the existing secret. A nonblank value
  explicitly replaces it; do not support revealing the current password.
- Fail the connector closed so it cannot start without a valid master key, while
  keeping the HTTP/admin application available for repair.
- Document that losing or changing the master key makes saved connector secrets
  unusable until the password is entered again.

### Connection target safety

Because this feature makes an outbound connection to an administrator-supplied
host:

- require JWT admin authentication for all connector endpoints;
- validate lengths and reject credentials embedded in host or virtual-host
  fields;
- support an optional `TENGEN_RABBITMQ_ALLOWED_HOSTS` deployment allowlist and
  require it in production;
- apply bounded DNS, TCP, TLS, and AMQP handshake timeouts; and
- never include the password or complete connection URI in errors, logs, or
  metrics.

Private network hosts are valid RabbitMQ targets, so reuse of the public-only
webhook destination policy would be incorrect.

## Connector Admin API

Add JWT-protected endpoints under `/api/connectors/rabbitmq`:

- `GET /api/connectors/rabbitmq` returns the safe configuration and runtime
  status without any password material.
- `PUT /api/connectors/rabbitmq` saves a disabled draft. Connection settings may
  be edited only while the connector is disabled.
- `POST /api/connectors/rabbitmq/test` tests the saved configuration without
  consuming a message and records the tested configuration version.
- `POST /api/connectors/rabbitmq/enable` requires a successful test of the same
  configuration version, validates the selected API key, and starts the
  listener.
- `POST /api/connectors/rabbitmq/disable` stops the listener and closes its AMQP
  resources while retaining the configuration.

Use optimistic configuration versioning so stale browser tabs cannot overwrite
newer settings. Make Enable and Disable idempotent.

The connection test should:

1. decrypt the saved password;
2. create a temporary connection with bounded timeouts;
3. authenticate to the configured virtual host;
4. passively declare the input queue;
5. passively declare the dead-letter exchange;
6. close all temporary channels and connections; and
7. return a safe structured result.

AMQP alone cannot verify every exchange binding without publishing a message.
Mandatory publishing and publisher returns must therefore enforce dead-letter
routing during real failure handling.

## Data Model

Use the next Flyway migration, currently expected to be
`V11__rabbitmq_connector.sql`.

### `rabbitmq_connectors`

Persist one connector definition:

- ID and stable bounded connector key;
- display name;
- host, port, virtual host, TLS flag, and username;
- password ciphertext, nonce, and encryption-key version;
- input queue, dead-letter exchange, and dead-letter routing key;
- selected API-key ID;
- enabled desired state;
- maximum body size and retry settings;
- configuration version and last successfully tested version;
- last tested timestamp; and
- created and updated timestamps.

Do not persist the volatile connection/listener status as authoritative state.
The runtime manager reports current status, while `enabled` represents desired
state. Do not hard-delete connector rows in the MVP; disable and update them.

### `rabbitmq_message_receipts`

Persist:

- ID;
- connector ID;
- bounded queue name and publisher `message_id`;
- source exchange and routing key when present;
- API-key ID used for authorization;
- nullable event ID with `ON DELETE SET NULL`;
- processed timestamp; and
- a unique constraint on `(connector_id, queue_name, message_id)`.

A committed row means event processing completed. Do not persist a `PROCESSING`
row in a separate transaction; an unsuccessful processing attempt must roll back
its reservation automatically.

Add indexes for event ID and processed time. Never persist RabbitMQ delivery
tags as identities.

### Event ingestion metadata

Add additive event metadata:

- `ingestion_origin` with values `HTTP` and `RABBITMQ`, backfilling existing rows
  to `HTTP` and making it non-null; and
- nullable connector ID for RabbitMQ-origin events.

Queue, exchange, routing key, and message ID remain in the receipt table and are
loaded only for event details. Do not place AMQP metadata in the event JSON
payload.

## Backend Event-Processing Refactor

Extract a package-internal authorized event processor from `EventService`:

- Input: validated `EventRequest`, resolved `ApiKey`, and a fixed ingestion
  context (`HTTP` or `RABBITMQ`, with safe connector metadata).
- Output: persisted event identity plus the full internal `EventResponse` needed
  by HTTP projection.
- Responsibility: future-skew check, API-key scope validation, watermark
  classification, event persistence, rule evaluation, trace creation, trigger
  state, and durable webhook intent.

Keep these responsibilities outside the core processor:

- HTTP filter authentication and rate limiting;
- HTTP idempotency reservation/replay;
- `FULL` versus `COMPACT` HTTP response projection;
- RabbitMQ receipt reservation, retry, dead-lettering, and acknowledgement; and
- connector configuration, secret handling, and listener lifecycle.

The HTTP controller and response contract must remain byte-compatible for
existing keys. Add regression tests before moving code.

## RabbitMQ Runtime Design

- Add the Boot-managed `spring-boot-starter-amqp` dependency without pinning a
  conflicting Spring AMQP version.
- Do not use global `spring.rabbitmq.*` credentials for the managed connector;
  construct a connector-scoped `CachingConnectionFactory`, `RabbitTemplate`, and
  listener container from the encrypted database configuration.
- Create and manage a `SimpleMessageListenerContainer` programmatically so the
  UI can start, stop, and rebuild it at runtime.
- Use manual acknowledgement, prefetch `1`, one consumer, and one-message
  processing.
- Receive raw bytes followed by explicit JSON deserialization so malformed
  values can be classified and dead-lettered predictably.
- Use a separate transactional service bean for receipt reservation plus the
  core-processing call so Spring transaction interception completes before
  acknowledgement.
- Use a publisher connection configured with correlated publisher confirms,
  publisher returns, and mandatory routing for dead-letter publication.
- Close and replace all connection factories, templates, channels, and listener
  containers after configuration changes; do not leak runtime resources.
- On startup, reconcile the single saved connector. Start it if enabled and
  valid; otherwise keep it paused and expose the safe error through the admin
  API.

The listener must not invoke the local HTTP endpoint. It calls the shared core
processor directly so database work remains one transaction and no raw Tengen
API key is needed in internal HTTP traffic.

The selected API key is a persisted policy association. The connector resolves
it by ID and still enforces active, expiry, type, and source rules before each
event.

## Admin UI

Add a **Connectors** navigation item and a RabbitMQ page or panel separate from
visual Preferences.

The UI should show:

- a short explanation that the backend, not the browser, connects to RabbitMQ;
- the connection form and masked password state;
- Save, Test connection, Enable, and Disable actions;
- the selected API key and its active/expiry status;
- desired state and runtime state (`DISABLED`, `TESTING`, `CONNECTING`,
  `RUNNING`, `PAUSED`, or `ERROR`);
- safe fixed error category and last transition time;
- last successful connection test and configuration version; and
- a reminder that the queue and dead-letter exchange must already exist.

Require confirmation before disabling an active connector. Disable editing of
connection fields while it is running. Do not render the password returned from
state, because the API never returns one.

## Event Explorer

Extend event history summaries/details with:

- ingestion origin;
- connector ID/name for RabbitMQ events; and
- queue, source exchange, routing key, and message ID on the event detail page
  when the receipt still exists.

Add an origin filter (`HTTP`, `RABBITMQ`) and origin chips. Do not expose broker
host, port, virtual host, username, password, arbitrary headers, or connection
settings from Event Explorer.

Message IDs are administrator-visible in event details but should be
fingerprinted rather than emitted raw in application logs or metric labels.

Dead-letter messages remain in RabbitMQ. Provide status, documentation, and
metrics, not a Tengen dead-letter browser.

## Health, Metrics, and Logging

- Keep HTTP/admin application readiness available when the connector fails so
  an administrator can repair it through the UI.
- Expose connector health separately through the protected connector API and an
  actuator health component.
- Count accepted, deduplicated, retried, and dead-lettered messages.
- Gauge listener running state and, when available through a passive queue
  declaration, approximate ready-message count without requiring the management
  plugin.
- Limit metric labels to connector ID and fixed result/category values.
- Log connector ID, queue, event ID after success, attempt, duration, and fixed
  failure category.
- Never log message bodies, passwords, connection URIs, encryption material,
  API-key data, rule expressions, callback URLs, arbitrary AMQP headers, or raw
  message IDs.

## Retention

RabbitMQ receipts protect deduplication only while retained. Reuse the global
operational retention worker with an explicit rule:

- delete receipts only when `processed_at` is older than the configured
  retention cutoff; and
- warn operators that redelivering a message whose receipt has expired can
  process that old event again.

Delete event rows independently using `ON DELETE SET NULL` on the receipt.
Require publishers not to reuse message IDs inside the documented receipt
retention horizon.

## Local Development

Add an optional Compose profile for RabbitMQ with the management image for local
inspection. It must not start in the default HTTP-only stack.

Document:

- AMQP port `5672` and optional local management UI port `15672`;
- creation of the durable input queue, dead-letter exchange, binding, and a
  least-privilege development user;
- how the backend container addresses the RabbitMQ service by Compose service
  name rather than `localhost`;
- how to set `TENGEN_CONNECTOR_MASTER_KEY`; and
- a sample publisher that sets persistent delivery mode, `application/json`,
  and a unique `message_id`.

Production operators may connect to an external RabbitMQ cluster instead.

## Implementation Sequence

1. Add the RabbitMQ connector, encrypted-secret, receipt, and ingestion-origin
   schema.
2. Add secret encryption and configuration validation with production guards.
3. Extract and regression-test the authorized core event processor.
4. Add the RabbitMQ connector admin API and safe response models.
5. Add the Connectors UI with save and connection-test workflows.
6. Add the runtime connection factory, listener lifecycle, transactional receipt
   processing, and manual acknowledgement.
7. Add bounded retry, confirmed dead-letter publishing, and connector pause
   behavior.
8. Add Event Explorer origin/metadata support.
9. Add health, metrics, retention, Compose profile, and documentation.
10. Run focused tests, full backend tests, frontend lint/tests/build, and the
    isolated RabbitMQ integration suite.

## Verification Plan

### Unit tests

- Encryption round-trips with AES-GCM and rejects tampered ciphertext.
- APIs and logs never expose plaintext password, ciphertext, nonce, master key,
  or complete connection URI.
- Connector validation rejects invalid hosts, ports, virtual hosts, queue names,
  retry settings, and production-disallowed targets.
- A blank password update preserves the existing encrypted secret.
- Enable requires a successful test of the current configuration version.
- RabbitMQ JSON mapping and Jakarta validation match the HTTP request contract.
- Missing/blank `message_id` is rejected before core processing.
- Delivery tags are never used for durable deduplication.
- API-key active, expiry, type, and source checks are enforced.
- Failure classification selects retry, dead-letter, or connector pause without
  exposing sensitive values.

### PostgreSQL and RabbitMQ integration tests

- Saving connection settings encrypts the password in PostgreSQL and GET
  responses return only `passwordConfigured`.
- Test connection validates a real isolated RabbitMQ queue without consuming a
  message.
- Enable starts the listener and Disable stops it without an application
  restart.
- An enabled connector starts again after a simulated application restart.
- A valid message creates one event, normal rule outcomes, and at most one
  webhook intent before the delivery is acknowledged.
- Redelivering the same connector/queue/message ID creates no second event and
  does not mutate rule or watermark state.
- A simulated crash after database commit and before acknowledgement is safe on
  redelivery.
- A database failure leaves the delivery unacknowledged and retries it.
- Malformed, oversized, and permanently invalid messages reach the dead-letter
  route; the original is acknowledged only after confirmed publication.
- A missing or unroutable dead-letter exchange leaves the original
  unacknowledged and pauses the connector.
- A revoked/expired connector API key pauses consumption instead of filling the
  dead-letter queue.
- A `TOO_LATE` RabbitMQ event is retained once and creates no rule/action state,
  matching HTTP semantics.
- Prefetch `1` and one consumer prevent the next message from overtaking a
  message undergoing local retry.

Use isolated PostgreSQL and RabbitMQ Testcontainers. Keep development and
production brokers untouched by automated tests.

### Frontend tests

- Connection fields save through the connector API.
- Password fields are replace-only and never populated from GET responses.
- Test, Enable, and Disable actions render the returned status correctly.
- Connection fields cannot be edited while running.
- Safe connection failures are understandable and contain no credential data.
- Event origin filtering sends `RABBITMQ` correctly.
- HTTP and RabbitMQ origin chips render correctly.
- RabbitMQ metadata appears only when present.

### Security tests

- Connector endpoints reject unauthenticated requests.
- Production startup leaves an enabled connector paused when its master key or
  allowed-host policy is missing, while the HTTP/admin application remains
  available.
- Decryption failure pauses the connector without logging secret material.
- Connection-test errors are mapped to fixed categories and do not echo broker
  exception strings directly.
- Saved and returned JSON never contains raw passwords or encryption fields.

## Acceptance Criteria

The feature is complete when an administrator can open the Connectors page,
enter actual RabbitMQ connection details, save them, test the backend connection,
and enable the connector without restarting Tengen. Publishing a correctly
formed JSON message with a unique AMQP `message_id` to the configured queue must
create exactly one accepted Tengen event with normal rule and webhook behavior.

The UI must show the connector as running. Forced redelivery must not duplicate
processing, and a malformed message must reach the configured dead-letter route
before the original is acknowledged. Disabling the connector must stop queue
consumption while leaving HTTP ingestion and the rest of the admin UI unchanged.

## Risks and Mitigations

- **Users expect the browser to consume RabbitMQ.** Explain in the UI that the
  backend opens the connection and consumes the queue.
- **Broker credentials are stored unsafely.** Encrypt passwords with AES-GCM and
  an environment-supplied master key; never return or log secret material.
- **The master key is lost or changed.** Fail closed and require the RabbitMQ
  password to be entered again.
- **Delivery tag is mistaken for a stable message identity.** Require a unique
  publisher `message_id` and key receipts by connector, queue, and message ID.
- **Database commit succeeds but acknowledgement is lost.** Use a durable
  receipt and treat redelivery as success without reprocessing.
- **The original is acknowledged before dead-letter publication is safe.** Use
  mandatory publishing, publisher returns, and publisher confirms before ack.
- **A dead-letter route is unavailable.** Leave the original unacknowledged,
  pause the connector, and show a safe repairable error.
- **A connection target is abused for internal network access.** Require admin
  authentication, bounded timeouts, validation, and a production host allowlist.
- **An enabled configuration is edited underneath a listener.** Require Disable,
  Save, Test, then Enable, with optimistic configuration versioning.
- **Multiple consumers break ordering.** Require a queue dedicated to one Tengen
  backend replica with concurrency and prefetch fixed at `1` for the MVP.
- **Refactoring changes HTTP semantics.** Add full/compact, idempotency,
  watermark, outcome, and outbox regression coverage before enabling RabbitMQ.
- **Sensitive message data leaks through diagnostics.** Use fixed categories,
  bounded safe metadata, message-ID fingerprints, and explicit negative log
  tests.
- **Old redelivery exceeds receipt retention.** Document the deduplication
  horizon and require publishers not to reuse message IDs within it.

## Technical Basis

- Spring Boot provides RabbitMQ support through `spring-boot-starter-amqp`.
- Spring AMQP supports programmatically created listener containers, which are
  needed for UI-driven start and stop behavior.
- RabbitMQ manual acknowledgements allow Tengen to acknowledge only after the
  database transaction completes.
- RabbitMQ delivery tags are channel-scoped, so a publisher-supplied
  `message_id` is required for durable application deduplication.
- Publisher confirms and returns are required to distinguish a confirmed,
  routed dead-letter publish from an unroutable or failed publish.

Reference documentation:

- [Spring Boot AMQP](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)
- [Spring AMQP programmatic listener containers](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/using-container-factories.html)
- [RabbitMQ acknowledgements and publisher confirms](https://www.rabbitmq.com/docs/confirms)

## Follow-Up

After this slice, implement
[replay job controls and history](2026-08-04-1244-replay-job-controls-history-plan.md).
Additional broker types or multiple connector definitions should be planned from
a concrete integration need rather than generalizing this RabbitMQ MVP.
