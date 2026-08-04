# Absence Pattern Rules Plan

## Status: Completed — implementation and manual verification 2026-08-04 12:05 PHT

## Recommendation

Implement an `ABSENCE` rule that detects a starting event followed by no matching expected event within a configured event-time window. Build it on the existing watermark, rule-revision, durable outbox, cooldown, and Event Explorer foundations.

The first supported shape is:

```text
START event A
AND no EXPECTED event B for the same scope
within N event-time seconds
```

Example: a `payment.started` event is received, but no `payment.completed` event for the same `data.orderId` occurs within ten minutes.

## Current-State Findings

- `CONDITION`, `AGGREGATE`, and `SEQUENCE` rules are evaluated during event ingestion.
- Watermarks are durable per `(source, event_type)` and currently advance only when that stream receives an event.
- Too-late events are retained but do not evaluate rules or mutate runtime state.
- Sequence instances already provide revision-scoped, grouped, durable pattern progress.
- Webhook actions are persisted through the durable outbox and delivered asynchronously.
- Event Explorer stores matched-rule and action snapshots associated with the event that caused the match.
- Rule updates, activation changes, archive, and restore reset revision-scoped runtime state.
- The producer response is finalized during ingestion and is persisted for idempotent replay; it cannot be rewritten when an absence resolves later.

## Goals

- Add a configurable `ABSENCE` rule type with start and expected event predicates.
- Support one optional grouping field such as `data.orderId` or `data.userId`.
- Persist pending absence instances so application restarts do not lose deadlines.
- Satisfy an instance when the expected event arrives within its event-time window.
- Trigger an instance only after the expected stream's watermark closes the deadline.
- Advance idle expected streams safely so a truly absent event can eventually be detected.
- Queue eligible webhooks through the existing outbox without making HTTP calls in the absence worker.
- Keep rule testing side-effect free.
- Make pending, satisfied, triggered, and lifecycle-cancelled instances visible in Event Explorer.
- Preserve existing behavior for condition, aggregate, sequence, idempotency, and webhook delivery flows.

## Non-Goals

- Arbitrary negative expressions or nested NOT operators.
- More than one missing step, branching, OR paths, or reusable pattern events.
- Retraction or correction after an absence has triggered.
- Matching an expected event across multiple sources or event types.
- Multiple simultaneous pending instances for the same rule revision and group.
- Broker-specific event-time or partition watermark semantics.
- Replacing the current HTTP ingestion contract.

## Behavioral Contract

### Rule configuration

Add `ABSENCE` to `RuleType`. Reuse the current top-level `eventType`, `source`, and `conditionScript` fields for the starting event and add:

- `expectedEventType`
- `expectedSource`
- `expectedConditionScript`
- existing positive `windowSeconds`
- existing optional `groupBy`

Both conditions use Aviator and the existing event environment. Normalize `groupBy` with the same field-path behavior used by aggregate and sequence rules.

For the MVP, an absence rule supports `LOG` and `WEBHOOK`, but webhook `triggerMode` must be `EVERY_MATCH`. Cooldown remains allowed and is scoped by rule revision plus group key. Reject `EDGE` and `ONCE_PER_WINDOW` because a delayed absence instance is already a discrete match and has no synchronous false-to-true lifecycle.

### Instance cardinality

Maintain at most one `PENDING` instance for `(rule_id, rule_revision, scope_key)`.

- A matching start event creates an instance when none is pending.
- A later matching start event for the same scope does not refresh or postpone the existing deadline.
- A terminal instance does not block a future start event from opening a new instance.
- A blank `groupBy` uses the existing global scope.
- A configured but missing or overlong group value does not open or satisfy an instance.

Call out this one-instance policy in the rule form. Users who need concurrent expectations must group by a unique business identifier such as an order or transaction ID.

### Event ordering and window boundaries

Use `(occurred_at, event_id)` as the deterministic event-time order, consistent with sequence processing.

- The expected event must occur after the starting event in that total order.
- The expected event cancels the absence at or before `deadline_at`.
- `deadline_at = start_occurred_at + windowSeconds`.
- An expected event after the deadline does not satisfy the instance.
- A `TOO_LATE` expected event never changes an instance.
- A `LATE_ACCEPTED` expected event may satisfy a still-open instance.

### Closure and idle streams

An instance may trigger only when the watermark for its configured expected `(source, event_type)` is greater than or equal to `deadline_at`.

Current watermarks depend on observed event progress, so add scheduled idle advancement:

- Advance active absence routes toward `Instant.now() - allowedLateness`.
- Keep `watermark_at` monotonic.
- Permit a watermark to advance beyond the last observed `max_occurred_at`; keep `max_occurred_at` as the actual observed event high-water mark and make it nullable for routes registered before their first event.
- Apply the advanced watermark to normal event classification. An expected event at or before that watermark is therefore `TOO_LATE` and cannot retract an already triggered absence.
- Lock a route watermark before claiming or resolving its due absence instances. Event ingestion and the worker must use the same watermark-then-instance lock order.

Expose worker and idle-advancement settings under `tengen.absence.worker.*`, including enabled state, polling interval, batch size, and initial delay. Reuse the global allowed-lateness duration rather than introducing a second grace period.

### Producer response and idempotency

The start request does not report the absence rule as matched, queued, or suppressed because the outcome is not known yet. The expected request also does not report the absence as matched when it merely satisfies a pending instance.

Do not mutate a persisted idempotency response after the delayed outcome. Event Explorer is the authoritative place to observe absence progress and its eventual action.

### Rule testing

Single-rule testing accepts:

- one required starting event sample;
- one optional expected event sample.

Return an `AbsenceTestResult` describing start match, expected match, correlation, ordering, window membership, and the simulated outcome:

- `WOULD_TRIGGER` when the start matches and no expected sample is supplied;
- `WOULD_BE_SATISFIED` when a matching expected sample is in scope and in time;
- `START_NOT_MATCHED`, `EXPECTED_NOT_MATCHED`, `CORRELATION_MISMATCH`, or `OUTSIDE_WINDOW` otherwise.

Testing assumes the deadline has closed only for the simulation. It must not persist events, open instances, advance watermarks, change cooldown state, create trace rows, or queue webhooks. All-rule testing reports an absence rule as not yet matched from a single candidate event.

## Data Model

### `rules`

Add nullable columns:

- `expected_event_type varchar(100)`
- `expected_source varchar(100)`
- `expected_condition_script text`

Update the rule-type check constraint to include `ABSENCE`. Existing rows remain unchanged. Application validation, not a database check spanning nullable columns, enforces complete absence configuration.

### `rule_absence_instances`

Create a runtime table with:

- `id`
- `rule_id`
- `rule_revision`
- `scope_key`
- `start_event_id`
- `start_occurred_at`
- `deadline_at`
- `status`: `PENDING`, `SATISFIED`, `TRIGGERED`, or `CANCELLED`
- nullable `resolved_by_event_id`
- nullable `resolved_at`
- nullable `delivery_id`
- nullable `suppression_reason`
- `created_at`
- `updated_at`

Add:

- a partial unique index on `(rule_id, rule_revision, scope_key)` where status is `PENDING`;
- a due-work index on `(status, deadline_at, id)`;
- a satisfaction lookup index on `(rule_id, rule_revision, scope_key, status, start_occurred_at, deadline_at)`;
- indexes on `start_event_id` and `resolved_by_event_id` for Event Explorer.

Reference the starting and resolving events with foreign keys. Store `delivery_id` without a foreign key, matching the current trace behavior so outbox retention does not strand terminal instances.

### Event outcome snapshot

Add nullable `absence_result jsonb` to `event_rule_outcomes`. The snapshot contains:

- absence instance ID;
- group key;
- starting event ID and occurrence time;
- expected event type and source;
- deadline;
- triggering watermark.

Insert the immutable matched-rule outcome against the starting event when the instance becomes `TRIGGERED`. Atomically increment the starting event's matched, queued, or suppressed trace counts. Event Explorer traces are therefore immediately complete for synchronous rules and eventually complete for absence rules.

## Backend Design

### Configuration and lifecycle

1. Extend `Rule`, `RuleRequest`, `RuleResponse`, `RuleSnapshot`, revision restore, and validation with the expected-event fields.
2. Require complete start and expected routes, valid expressions, a positive window, and valid grouping for `ABSENCE`.
3. Clear aggregate and sequence-only fields when applying an absence request.
4. Include absence fields in immutable revisions and stale-write comparisons.
5. On update, deactivate, archive, or restore, mark pending instances `CANCELLED`; never delete their audit state synchronously.
6. Update active-rule routing so both the start and expected routes select an absence rule without disturbing sequence routing.

### Ingestion-time absence evaluation

Add an `AbsenceRuleService` called by `EventService` for routed `ABSENCE` rules:

1. Resolve the group key once using the existing normalized path behavior.
2. Lock the pending instance for the rule revision and scope.
3. Evaluate the expected predicate first. If it matches and is ordered within the deadline, mark the pending instance `SATISFIED` and attach the resolving event.
4. Evaluate the start predicate. If it matches and no instance remains pending, insert a new `PENDING` instance.
5. If one event could match both predicates, satisfaction of an older instance occurs before creation of a new instance.
6. Return progress metadata to the ingestion service without treating start or satisfaction as a logical rule match.

Use the partial unique constraint as the final concurrency guard. On a competing insert, load the winning pending row instead of surfacing an error to the producer.

### Watermark idle advancement

Extend `EventWatermarkService` with a monotonic idle-advance operation and make time injectable through `Clock` for deterministic tests.

The absence worker should identify distinct expected routes with pending work, advance and lock each route watermark, then resolve only instances whose deadline is closed. Keep this work bounded by configured batch sizes and do not scan all historical routes on each poll.

### Due-instance worker

Add a scheduled `AbsenceEvaluationWorker` and a transactional claim/resolve service:

1. Select closed `PENDING` rows in deadline order with `FOR UPDATE SKIP LOCKED`.
2. Reload and verify that the rule is active, unarchived, valid, and still at the instance revision.
3. Mark stale or inactive work `CANCELLED`.
4. Mark eligible work `TRIGGERED` exactly once.
5. Dispatch the configured LOG or webhook action in the same database transaction.
6. Persist the `EventRuleOutcome`, absence result snapshot, delivery link or suppression reason, and updated event trace counts atomically.

No outbound HTTP call occurs in this worker. A rollback must leave the instance pending and create neither an outcome nor an outbox row.

### Action and outbox reuse

Extract the reusable webhook eligibility and reservation logic currently private to `EventService` into a focused `RuleActionService`. Synchronous matches and the absence worker should delegate to the same cooldown and outbox path.

Add an absence-specific outbox enqueue method that:

- uses the starting event as `webhook_outbox.event_id`;
- stores an `absences` payload map alongside the existing `aggregates` and `sequences` maps;
- uses `ABSENCE:rule=<id>:revision=<revision>:instance=<instanceId>` as the stable deduplication key;
- snapshots the rule name, callback URL, revision, cooldown, scope, and absence result.

Delivery retry and dead-letter behavior remain unchanged.

### Event Explorer and retention

- Extend event details with related absence instances so a starting event shows `PENDING`, `SATISFIED`, `TRIGGERED`, or `CANCELLED` even before a matched-rule outcome exists.
- Render the absence result in matched-rule details after triggering.
- Keep the existing delivery link through the starting event.
- Ensure event list match filters use the atomically updated trace counts after triggering.
- Delete terminal absence instances through bounded retention.
- Prevent deletion of an event while it is referenced by a pending absence instance.
- Keep pending instances regardless of age until they resolve or lifecycle cancellation occurs.

### Observability

Add:

- counters for instances opened, satisfied, triggered, lifecycle-cancelled, webhook-queued, and webhook-suppressed;
- a gauge for pending instances;
- worker error logs with instance, rule, revision, expected route, and deadline;
- a worker batch-duration timer and closed-instance count.

Do not log callback secrets, full payloads, API keys, or raw condition data.

## Frontend Changes

### Rule administration

- Add `ABSENCE` to frontend rule types.
- Show separate `Starting event` and `Expected event` sections, each with event type, source, and visual/raw Aviator condition editors.
- Show the positive window and optional group-by field with guidance to use a unique business key for concurrent expectations.
- Limit webhook trigger mode to `Every match`; keep cooldown in Advanced settings.
- Display absence rules in the list as `start type -> no expected type within Ns`.
- Include all new fields in create, edit, revision history, restore, and API types.

### Rule tester

- Show starting-event JSON and optional expected-event JSON inputs for a selected absence rule.
- Render the simulated outcome and explain that `WOULD_TRIGGER` assumes the watermark has closed.
- Keep the existing single-event tester for condition and aggregate rules and multi-step tester for sequence rules.

### Event Explorer

- Add an `Absence progress` section to event details.
- Show status, rule revision, group, deadline, expected route, resolving event link, triggering watermark, suppression reason, and delivery link when available.
- Render triggered absence details in the matched-rule table.
- Explain that producer responses are not updated when delayed absence outcomes resolve.

## Implementation Sequence

### Slice 1 — Event-time closure foundation

- Support nullable observed high-water marks for registered-but-idle routes.
- Add monotonic idle watermark advancement using the configured allowed lateness.
- Make watermark time deterministic with `Clock`.
- Verify that an event at or before an idle-advanced watermark is too late and cannot move progress backward.

### Slice 2 — Rule contract and persistence

- Add `ABSENCE` configuration fields, validation, snapshots, migration, DTOs, and frontend types.
- Add the absence instance table, statuses, indexes, and repository locking queries.
- Cancel pending instances during rule lifecycle changes.

### Slice 3 — Start and satisfaction processing

- Route both configured event patterns to absence rules.
- Create pending instances from matching starts.
- Satisfy instances from in-window expected events.
- Enforce grouping, ordering, boundary, one-pending-instance, late-event, and idempotency behavior.

### Slice 4 — Closure, action, and trace processing

- Add the bounded scheduled worker.
- Extract shared action reservation logic.
- Enqueue deduplicated absence webhooks.
- Persist triggered outcomes and update Event Explorer trace counts atomically.
- Add metrics, logging, and retention.

### Slice 5 — Admin UI and side-effect-free testing

- Add absence rule editing and list summaries.
- Add the two-event absence tester and result presentation.
- Add absence progress and matched-result displays to Event Explorer.
- Update documentation and the CEP roadmap only after implementation is complete.

## Verification Plan

### Backend unit and repository coverage

- Complete and invalid absence configuration.
- Rule request mapping, response mapping, revision snapshots, and restore.
- Start match opens one instance; repeated starts do not postpone it.
- Different group keys open independent instances.
- Missing or overlong group keys do not mutate state.
- Expected event satisfies only the matching group.
- Expected event boundary and same-timestamp event-ID ordering.
- Late-accepted expected events satisfy open instances; too-late events do not.
- Idle watermark advancement is monotonic and uses allowed lateness.
- Worker triggers only after watermark closure.
- Concurrent workers cannot trigger or enqueue the same instance twice.
- Lifecycle changes cancel pending instances.
- Cooldown suppression and webhook reservation use existing semantics.
- Worker rollback leaves no partial outcome or outbox row.
- Tester produces all result states without persistence or side effects.
- Retention preserves events referenced by pending instances.

Spring Boot integration tests must only be created or run after explicit user approval, per repository instructions. With approval, add focused PostgreSQL coverage for row locking, partial uniqueness, `SKIP LOCKED`, transactional rollback, lifecycle races, and outbox deduplication.

### Frontend coverage

- Absence form maps both predicates, window, group, action, and cooldown correctly.
- Required-field and trigger-mode validation is visible.
- Editing an absence rule restores both condition editors.
- Rule testing submits both samples and renders every simulated outcome.
- Event details render all absence statuses, resolving-event links, and delivery state.
- Existing condition, aggregate, and sequence form/test behavior remains unchanged.

Run focused frontend tests, followed by:

```bash
cd frontend
npm test
npm run lint
npm run build
```

### Manual verification

1. Create a webhook absence rule: `payment.started` followed by no `payment.completed` within 60 seconds, grouped by `data.orderId`.
2. Send a start and an in-window completion for order A; observe `SATISFIED` and no webhook.
3. Send only a start for order B; wait for the deadline plus allowed lateness and observe one `TRIGGERED` result and one outbox row.
4. Restart the backend while order C is pending; observe that it still resolves exactly once.
5. Send a completion at or before the closed watermark; observe `TOO_LATE` and no retraction.
6. Edit or deactivate a rule with a pending order D; observe `CANCELLED` and no action.
7. Replay the start request with the same idempotency key; observe the original response and no duplicate instance.

## Risks and Mitigations

- **Idle advancement changes dormant-stream classification.** Document that allowed lateness becomes the maximum accepted delay after wall-clock progress for active absence routes, keep advancement monotonic, and cover dormant-stream wake-up behavior.
- **Delayed outcomes differ from ingestion responses.** Keep idempotent producer responses immutable and clearly label Event Explorer as the eventual source of truth for absence state.
- **Duplicate starts create alert storms.** Allow only one pending instance per revision and scope and recommend a unique group key.
- **Concurrent completion and closure race.** Lock watermark before instance in both paths; once closure commits, an at-or-before-deadline completion is too late by definition.
- **Rule changes trigger stale work.** Revision-scope instances, cancel pending rows on lifecycle changes, and revalidate the active revision before triggering.
- **Worker failure creates partial state.** Resolve the instance, action reservation, outbox insert, outcome snapshot, and trace-count update in one transaction.
- **High-cardinality groups grow runtime data.** Use targeted indexes, bounded batches, metrics, and retention for terminal rows.
- **Extraction of action logic affects synchronous rules.** Keep public responses and trigger behavior unchanged and retain focused regression coverage for cooldown, edge, once-per-window, and outbox deduplication.

## Acceptance Criteria

The feature is complete when:

- An admin can create, edit, revise, restore, activate, and test an absence rule.
- A matching start opens one durable pending instance per rule revision and group.
- An expected event in the same group and event-time window satisfies the instance without matching or sending an action.
- Different groups never satisfy one another.
- A pending instance triggers exactly once only after its expected route watermark closes the deadline, including when that route is otherwise idle.
- A completion at or before the closed watermark is classified too late and cannot retract the result.
- Triggered webhook actions use the durable outbox, existing retry behavior, cooldown reservations, and deterministic deduplication.
- Restarts and multiple application instances do not duplicate triggers or deliveries.
- Rule lifecycle changes cancel stale pending work.
- Event Explorer shows the full absence lifecycle and links a triggered result to its starting event and webhook delivery.
- Idempotent event replay does not create another absence instance or action.
- Existing condition, aggregate, sequence, response-mode, and webhook behavior remains compatible.
