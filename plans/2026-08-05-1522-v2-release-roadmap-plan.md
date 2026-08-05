# Tengen v2 Release Roadmap Plan

## Status: Proposed — 2026-08-05 15:22 PHT

This is a planning-only document. It does not implement any of the features
described here.

## Executive recommendation

Tengen already has a substantial CEP v1: HTTP and RabbitMQ ingestion, API-key
scoping and idempotency, condition/aggregate/sequence/absence rules, durable
event-time watermarks, asynchronous signed webhooks, delivery history, replay
job controls, retention, health checks, Prometheus metrics, and an Event
Explorer.

The next release should not begin with another isolated rule type. The most
valuable v2 step is to make the existing capabilities safe for team and
production adoption:

1. Replace the single environment-configured admin with persistent workspaces,
   users, roles, and tenant isolation.
2. Stabilize the API contract and publish an OpenAPI reference while keeping
   existing producers compatible.
3. Add governed rule publishing, durable audit history, first-class webhook
   destinations, and secret rotation.
4. Make ingestion limits and background workers safe across multiple backend
   replicas.
5. Add event schemas, operational dashboards, and data export/redaction before
   expanding the CEP language.

Advanced event correction, sequence/absence replay, and branching patterns are
important v2.x work, but they depend on explicit event-time and side-effect
semantics. They should not be mixed into the first platform-hardening cut.

## Product assumption

This roadmap assumes v2 targets multiple teams or production customers rather
than only one self-hosted administrator. If Tengen will remain a single-team
internal service, defer the workspace and RBAC slice and bring schema
contracts, operational dashboards, and replay parity forward instead.

## Current-state assessment

| Area | Already present | v2 gap to close |
| --- | --- | --- |
| Authentication | JWT access/refresh sessions and throttled login | `AdminUser` is an environment-backed singleton and every protected route is effectively `ROLE_ADMIN`; there are no persistent users, workspaces, or resource permissions. |
| Rule lifecycle | Immutable revisions, restore, archive/unarchive, ETags, validation, actor text | There is no draft/review/approval/publish workflow, scheduled activation, or durable audit stream covering all admin mutations. |
| CEP patterns | Condition, aggregate, linear 2–5 step sequence, and one-start/one-expected-event absence rules | No correction/retraction model, branching or parallel patterns, reusable event clauses, multi-dimensional correlation, or general pattern composition. |
| Event time | Durable source/type watermarks, bounded lateness, too-late suppression, idle advancement for absence routes | Late accepted events do not reopen prior results; there is no correction, retraction, outcome supersession, or compensating action contract. |
| Replay | Isolated, analysis-only replay for `CONDITION` and `AGGREGATE`, with pause/resume/cancel/retry and history | `SEQUENCE` and `ABSENCE` are rejected; there is no comparison report, export, or approved action-preview/apply workflow. |
| Webhooks | Durable outbox, retries, leases, dead letters, signed delivery, manual retry, delivery search/detail | Rules store callback URLs directly and delivery signing uses one global secret; there is no destination registry, secret rotation, per-attempt history, bulk retry, or endpoint circuit breaking. |
| Ingestion | HTTP and one UI-managed RabbitMQ connector; API-key scopes and response modes | HTTP rate limiting and login throttling are in-memory; RabbitMQ is intentionally single-connector/single-replica; no schema registry, batch contract, or connector ownership protocol exists. |
| Operations | Health/readiness, Prometheus endpoint, structured logs, queue gauges, retention cleanup | No built-in operations dashboard, SLO/alert definitions, tenant-aware metrics, archive/export workflow, or configurable PII redaction. |
| API/developer experience | Documented unversioned `/api/*` endpoints and a Next.js console | No generated OpenAPI contract, version/deprecation strategy, SDKs, cursor pagination, or producer compatibility test suite. |

The assessment is based on the current implementation in
[`AdminUser`](../tengen/src/main/java/com/tengencorp/tengen/security/AdminUser.java),
[`SecurityConfig`](../tengen/src/main/java/com/tengencorp/tengen/config/SecurityConfig.java),
[`RuleLifecycleService`](../tengen/src/main/java/com/tengencorp/tengen/service/RuleLifecycleService.java),
[`EventWatermarkService`](../tengen/src/main/java/com/tengencorp/tengen/service/EventWatermarkService.java),
[`ReplaySnapshotValidator`](../tengen/src/main/java/com/tengencorp/tengen/service/ReplaySnapshotValidator.java),
[`WebhookClient`](../tengen/src/main/java/com/tengencorp/tengen/service/WebhookClient.java),
[`ApiKeyRateLimiter`](../tengen/src/main/java/com/tengencorp/tengen/service/ApiKeyRateLimiter.java),
and the existing Flyway migrations.

## Recommended release slices

### Slice 0 — v2 contract and release gates

Priority: P0 · prerequisite for all other slices

Before implementation, agree on the product boundary and write down the
compatibility promises:

- Define whether v2 is multi-tenant, single-tenant with teams, or a strictly
  internal deployment. The recommendation is workspace isolation with one or
  more users per workspace.
- Freeze the v1 behavior of `POST /api/events`, idempotency, too-late events,
  webhook delivery, and replay action mode.
- Choose the API strategy. Recommended: preserve the existing unversioned
  routes as compatibility adapters, introduce new administrative contracts
  under `/api/v2`, and never silently change producer response semantics.
- Define initial SLOs and capacity targets: ingestion p95 latency, accepted
  events per second, webhook queue age, replay completion time, availability,
  recovery point objective, and recovery time objective.
- Define data classes and retention requirements for raw event payloads,
  webhook payloads, rule expressions, credentials, and audit records.
- Create a migration/backfill runbook and require a PostgreSQL backup before
  production migrations.

Exit criteria:

- A short v2 contract document exists for identity, API compatibility,
  event-time behavior, delivery guarantees, retention, and SLOs.
- Every proposed v2 feature has an owner, dependency, migration plan, and
  measurable acceptance criteria.

### Slice 1 — Persistent identity, workspaces, and RBAC

Priority: P0 · effort: XL · dependency: Slice 0

Replace the environment-backed singleton admin model with a persistent
identity boundary.

Backend and data scope:

- Add workspaces/tenants, users, memberships, roles, and permission checks.
- Backfill all existing data into a default workspace without changing event or
  delivery IDs.
- Add workspace ownership to rules, API keys, events, connectors, replays,
  webhook destinations, settings, operational records, and audit events.
- Replace global unique constraints such as rule names with workspace-scoped
  constraints where appropriate.
- Add session revocation by user/workspace and retain refresh-token replay
  protection.
- Keep the first role matrix small: `OWNER`, `ADMIN`, `OPERATOR`, and `VIEWER`.
  Viewers may inspect; operators may run/retry operational jobs; admins may
  manage rules, keys, destinations, and connectors; owners may manage users and
  workspace settings.

Frontend scope:

- Workspace switcher and current-workspace context.
- User/member administration, invitations or a documented provisioning path,
  role editing, deactivation, and a clear forbidden state.
- Hide or disable actions the current role cannot perform; enforce permissions
  again in the backend.

Acceptance criteria:

- A user cannot read or mutate another workspace's rules, events, keys,
  deliveries, replay jobs, connector settings, or audit entries.
- Existing single-admin installations start with one migrated workspace and
  remain usable through a compatibility migration.
- Audit records identify a persistent user and workspace, not only a free-form
  actor string.

### Slice 2 — Versioned API, OpenAPI, and compatibility surface

Priority: P0 · effort: L · dependency: Slice 1

Make the backend safe to integrate with from outside the console.

Scope:

- Publish an OpenAPI contract for producer, admin, replay, connector, delivery,
  and authentication endpoints.
- Define a consistent error envelope with stable machine-readable codes,
  request IDs, validation details, and safe human messages.
- Document authentication, idempotency, pagination, ETags/`If-Match`, rate
  limits, event-time statuses, webhook delivery guarantees, and deprecations.
- Add explicit v2 request/response types instead of exposing JPA entities or
  allowing frontend DTO drift.
- Add cursor pagination for high-volume event, delivery, and audit lists while
  retaining page-number compatibility where current clients rely on it.
- Add contract tests for the producer response modes and the
  `X-Idempotency-Replayed` header.
- Generate a minimal TypeScript client for the console and a documented
  producer example/SDK path; do not hand-maintain a second API description.

Acceptance criteria:

- A new producer can integrate from the generated reference without reading
  backend source code.
- Existing v1 producers continue to receive the documented response shape and
  status codes.
- A deprecation policy exists for any route or field that will eventually be
  removed.

### Slice 3 — Rule governance and durable audit history

Priority: P0 · effort: L · dependency: Slices 1–2

Turn current revision history into a controlled production release workflow.

Scope:

- Add explicit rule states for draft, validated, published/active, paused, and
  archived while preserving the current `active`/archive compatibility model.
- Require a successful test result or an explicit override permission before
  publishing a revision.
- Add review/approval records with actor, timestamp, revision, decision, and
  optional comment; support at least one approval for production publishing.
- Add scheduled activation/deactivation and a preview of the runtime state that
  will be reset when a revision is published.
- Add revision diff views for conditions, thresholds, grouping, sequence steps,
  absence expectations, action configuration, and destination references.
- Add a durable audit event table for user, key, rule, connector, destination,
  replay, delivery-retry, and settings mutations. Logs remain useful for
  diagnostics but are not the system of record for audit.
- Expose rule state, revision, approval, and audit history in the console.

Acceptance criteria:

- An operator cannot accidentally activate an unreviewed revision.
- A published revision is immutable and its runtime state is clearly scoped to
  that revision.
- A reviewer can answer who changed, approved, published, paused, restored, or
  retried an operation without searching application logs.

### Slice 4 — First-class destinations and webhook safety

Priority: P0 · effort: L · dependency: Slices 1–3

Decouple reusable delivery configuration from rule definitions and make secret
lifecycle explicit.

Scope:

- Add workspace-scoped webhook destinations with safe display name, URL,
  allowed headers, authentication mode, timeout/retry policy, and status.
- Encrypt destination credentials at rest and support secret versioning and
  overlap during rotation. Prefer a deployment secret manager integration for
  production; keep local development configuration documented.
- Replace the global signing-secret assumption for new destinations with a
  per-destination signing key. Preserve the current immutable callback URL and
  payload snapshot in existing outbox rows.
- Add a destination test action that never creates a live rule match or changes
  cooldown/trigger state.
- Add per-attempt delivery records with timestamp, duration, status, bounded
  error, and an allowlisted set of response headers. Never store authorization
  headers or arbitrary sensitive response data.
- Add bounded bulk retry for dead-lettered deliveries with confirmation,
  permission checks, idempotent selection, and an audit record.
- Add per-destination concurrency/rate limits and a circuit-breaker or
  backoff-open state so one failing endpoint cannot consume the whole worker.

Acceptance criteria:

- A rule references a managed destination while each outbox row still contains
  enough immutable data to deliver safely after a rule or destination edit.
- Rotating a destination secret does not invalidate in-flight deliveries and
  receivers can verify both keys during the configured overlap.
- An administrator can trace a delivery from rule to event to attempt history,
  retry only the intended rows, and see the next operational action.

### Slice 5 — Multi-replica runtime and ingestion controls

Priority: P0 · effort: XL · dependency: Slices 1–4

Remove the remaining single-instance assumptions before claiming horizontal
scaling.

Scope:

- Keep webhook and replay lease claims safe under multiple replicas; add
  explicit node identity, lease diagnostics, graceful shutdown, and stale-lease
  recovery tests.
- Move API-key rate limiting and login throttling from process-local maps to a
  shared or database-backed algorithm with bounded cleanup and per-workspace
  quotas.
- Define RabbitMQ ownership and ordering for multiple replicas. Recommended
  first step: a durable connector lease that permits one active consumer per
  ordered connector, with an explicit opt-in for competing consumers and
  higher throughput.
- Support multiple workspace-scoped RabbitMQ connectors only after ownership,
  queue ordering, receipt identity, dead-letter behavior, and secret rotation
  are specified.
- Add graceful worker shutdown and readiness behavior so a node stops accepting
  new work before terminating.
- Add capacity indexes/partitioning measurements for `events`, `rule_events`,
  `webhook_outbox`, receipts, and audit records; partition only where measured
  volume justifies the operational cost.

Acceptance criteria:

- Two backend replicas can ingest, evaluate, deliver, and recover work without
  duplicate logical state transitions.
- Rate limits apply consistently across replicas.
- A RabbitMQ connector has one documented ordering/ownership behavior under
  restart, failover, and concurrent deployment.
- A node shutdown leaves no permanently stuck lease or unacknowledged message.

### Slice 6 — Event schemas and producer contracts

Priority: P1 · effort: L · dependency: Slice 2, preferably Slice 1

Give rules a dependable data contract instead of relying only on `type`,
`source`, and arbitrary JSON.

Scope:

- Add workspace-scoped event schemas keyed by event type/source and version.
- Support JSON Schema first; treat CloudEvents, Avro, and Protobuf as later
  adapters rather than adding all formats to v2.0.
- Validate schema compatibility when a new version is registered and expose a
  sample payload/test tool in the console.
- Allow API keys to be restricted to schema versions or schema families in
  addition to current type/source scopes.
- Define invalid-event handling: rejected response for HTTP, durable broker
  dead-letter with a safe reason, and no rule state/action mutation.
- Display schema version and validation outcome in Event Explorer and producer
  responses where applicable.

Acceptance criteria:

- A producer can discover the accepted contract before sending traffic.
- A breaking schema change is rejected or explicitly versioned.
- Invalid events are explainable and cannot create partial rule state or
  webhook intent.

### Slice 7 — Event-time correction and retraction semantics

Priority: P1 · effort: XL · dependency: Slices 3 and 6

This is the most important CEP correctness feature after the current watermark
foundation, and the highest-risk item in the roadmap. Decide the behavior before
writing migrations.

Required design decisions:

- Choose the stable producer identity for an event correction: explicit event
  ID, producer key plus event ID, or another documented identity. Do not infer
  identity from a payload hash.
- Define operations such as append, replace, and retract, their idempotency
  behavior, and whether an event may be corrected after a watermark closes.
- Define the bounded recomputation window for aggregates, sequence progress,
  absence instances, trigger state, and event traces.
- Define whether a correction emits a compensating webhook, a new correction
  webhook, or only updates console state. The existing at-least-once webhook
  contract must remain honest.
- Define outcome supersession and audit history so users can distinguish the
  original decision from the corrected decision.
- Define behavior for already delivered actions, dead-lettered actions, and
  rules that have since been archived or revised.

Implementation scope after the decisions are approved:

- Store event operation/identity metadata and immutable correction records.
- Recompute only affected revision-scoped state, with leases and deduplication.
- Preserve original producer responses and expose the eventual corrected state
  in Event Explorer.
- Add correction-specific action policies and operator visibility.

Acceptance criteria:

- A correction cannot silently mutate unrelated rules, groups, revisions, or
  tenants.
- Reprocessing is bounded, restart-safe, and idempotent.
- The UI and API explain when an outcome was superseded and what happened to a
  previously queued or delivered action.

### Slice 8 — Replay parity and safe backfill

Priority: P1 · effort: XL · dependency: Slice 7

Extend the isolated replay system only after live event-time behavior is
specified.

Scope:

- Add replay evaluators for sequence and absence rules with job-local progress,
  closure/watermark semantics, and deterministic finite-range behavior.
- Add a comparison mode that reports differences between a selected revision,
  current live traces, and a proposed revision without mutating production.
- Add exportable results and bounded result retention.
- Keep `NO_ACTIONS` as the default and add a separately permissioned
  `PREVIEW_ACTIONS` mode that computes intended actions without sending them.
- Consider `APPLY`/backfill only after correction, deduplication, trigger,
  webhook, and watermark reset policies are explicit. It is not part of the
  recommended v2.0 cut.

Acceptance criteria:

- Sequence and absence replay never write live state or delivery rows.
- Re-running the same job is deterministic and produces no duplicate outcomes.
- Operators can compare rule revisions and export results before approving any
  operational change.

### Slice 9 — Advanced CEP patterns and state explainability

Priority: P1/P2 · effort: XL · dependency: Slices 6–8

Expand the pattern language only after the event model is stable.

Recommended first capabilities:

- Branching/alternate sequence paths and parallel steps.
- Multiple expected events and nested/terminal absence conditions.
- Reusable event clauses and named predicates so the same business condition
  is not copied into every step.
- Multiple correlation keys or a bounded correlation expression with a clear
  cardinality limit.
- Pattern instances/state inspection: active sequence progress, pending absence
  expectations, aggregate values, trigger reservations, and why an event did or
  did not advance a pattern.
- A versioned pattern representation rather than an ever-growing set of
  nullable columns on `rules`.

Acceptance criteria:

- A pattern can be tested with representative event timelines before publish.
- State growth is bounded, observable, revision-scoped, and covered by
  retention.
- The console explains branch selection, correlation, timeout, and suppression
  reasons without exposing raw secrets or unsafe expressions.

### Slice 10 — Operations center, retention, and data governance

Priority: P1 · effort: L · dependency: Slice 1 and the v2 API

Turn existing health checks, metrics, and retention jobs into an operator-facing
control surface.

Scope:

- Add a dashboard for ingestion rate/errors, late and too-late events, rule
  matches, pending absence/sequence state, webhook backlog/age/dead letters,
  replay queues, RabbitMQ health, and worker lease recovery.
- Add alert definitions or documented Prometheus alert rules for backlog age,
  dead-letter growth, ingestion rejection rate, watermark lag, connector
  failure, and database/worker health.
- Add workspace-configurable retention for raw events, traces, deliveries,
  replay jobs, audit events, and broker receipts, with safe minimums for
  compliance records.
- Add export/archive to object storage or a documented offline export path for
  events, traces, deliveries, and audit records.
- Add field-level redaction for Event Explorer, webhook payload storage, logs,
  and exports; make the redaction policy versioned and explicit.
- Add data deletion/purge workflows with preview, authorization, audit, and
  dependency checks.

Acceptance criteria:

- An operator can detect and explain a backlog or connector outage without
  database access.
- Retention never deletes a live pending state or leaves orphaned references.
- Sensitive fields are not exposed by detail APIs, logs, exports, or the new
  dashboard.

### Slice 11 — Ingestion and integration ecosystem

Priority: P2 · effort: M–XL per connector · dependency: Slices 2, 5, and 6

Add only the producer paths justified by real adoption signals.

Candidates:

- Batch HTTP ingestion with per-event acceptance, idempotency, and partial
  failure reporting.
- Small producer SDKs or examples for the highest-value languages.
- Additional brokers such as Kafka or SQS only after a connector ownership,
  ordering, offset, secret, and dead-letter contract is written for that broker.
- Outbound event publishing or native integrations (Slack, PagerDuty, email,
  or similar) built on the destination abstraction rather than as special cases
  in rule evaluation.

Do not add a generic connector framework or claim exactly-once effects at an
external receiver without a concrete integration requirement.

## Recommended release boundary

### v2.0 — Production platform

Ship Slices 0–6 plus Slice 10:

- workspaces, users, roles, and isolation;
- stable API/OpenAPI and compatibility adapters;
- governed rule publishing and durable audit;
- managed destinations, secret rotation, attempt history, and safe retries;
- multi-replica worker/rate-limit/connector behavior;
- initial event schema registration and validation;
- operational dashboard, alerts, and minimum retention/redaction controls.

Defer correction/retraction and action-enabled backfill from the v2.0 release
unless the product has a confirmed use case and the event-time design is
approved first.

### v2.1 — CEP correctness and analysis

Ship Slices 7–9:

- correction/retraction and superseded outcomes;
- sequence/absence replay and revision comparison;
- preview-only action analysis;
- branching/parallel patterns, richer absence, reusable predicates, and state
  explainability.

### v2.2 — Ecosystem and scale

Ship Slice 11 items selected by measured demand, plus deeper partitioning,
batch ingestion, SDKs, and additional connectors.

## Cross-cutting verification plan

Every slice should include backend, frontend, migration, and documentation
coverage. The minimum v2 release gate is:

- Unit coverage for validation, state transitions, authorization, redaction,
  retry, lease recovery, and idempotency boundaries.
- Approved Spring Boot repository/controller/integration coverage for tenant
  isolation, migration backfill, concurrent ingestion, duplicate delivery
  prevention, RabbitMQ failover, and retention dependencies. Per the existing
  repository plans, do not create or run new Spring Boot integration tests until
  the user explicitly approves them.
- Frontend tests for role-aware actions, workspace context, rule governance,
  destination secrets, filters, redaction, and error states.
- Playwright flows for onboarding, rule draft-to-publish, event trace to
  delivery attempt, replay preview, and forbidden cross-workspace access.
- Load tests for ingestion, rule evaluation, outbox draining, replay workers,
  and high-cardinality grouping; measure database growth and query plans.
- Failure tests for node restart, database reconnect, worker lease expiry,
  RabbitMQ reconnect, duplicate messages, delayed events, and secret rotation.
- Security review covering tenant isolation, SSRF/destination validation,
  credential storage, JWT/session lifecycle, audit integrity, data exports, and
  log/payload redaction.
- Documentation updates in `README.md`, `documentation/index.html`, and
  `documentation/tengen-llm.txt` for every public contract or configuration
  change.

## Decisions to make before implementation

1. Is v2 a multi-tenant product, a single-tenant team product, or both?
2. Should v2 preserve unversioned `/api/*` routes indefinitely as adapters, or
   introduce a formal `/api/v1` migration first?
3. Which secret manager is supported in production, if any, and what is the
   local-development fallback?
4. What event identity enables replacement/retraction without payload-hash
   ambiguity?
5. Do corrections emit compensating actions, correction actions, or only state
   changes?
6. What SLOs and maximum cardinalities define a supported deployment?
7. Which one or two schema/connector integrations have confirmed users?

Until these decisions are made, the safest next implementation slice is Slice 0
followed by identity/RBAC and API contract work. The advanced CEP items should
remain roadmap entries rather than being started opportunistically.
