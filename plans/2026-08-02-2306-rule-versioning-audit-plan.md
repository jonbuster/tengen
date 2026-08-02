# Rule Versioning and Audit History Plan

## Status: Implemented — 2026-08-02

## Recommendation

Rule versioning and audit history are implemented. This closes a correctness gap in the prior implementation: aggregate rows, webhook trigger state, and webhook deduplication are now scoped by rule revision so editing a rule cannot reuse state created by an earlier definition.

This feature should be implemented as a version boundary for runtime state, not only as a change-log screen.

## Current-State Findings

- `Rule` stores only `createdAt` and `updatedAt`; it has no revision number or immutable history.
- Create, update, toggle, and delete operations write directly through `RuleRepository` in `RuleAdminController`.
- Aggregate queries use only rule ID, group key, and event time. An edited aggregate rule can therefore reuse matching rows created under an earlier configuration.
- Cooldown, `EDGE`, and once-per-window state use rule ID without a revision dimension.
- Webhook outbox rows snapshot delivery details, but their deduplication keys do not identify the rule revision.
- The current delete endpoint performs a hard delete even though rule events and trigger-state rows hold foreign keys to the rule.
- The frontend has edit, toggle, and delete controls but no history, comparison, archive, or restore workflow.

## Goals

- Give every rule configuration an increasing revision number.
- Store an immutable snapshot for every meaningful lifecycle change.
- Prevent aggregate and webhook trigger state from leaking between revisions.
- Preserve queued webhook behavior using the revision that originally created the delivery.
- Replace unsafe hard deletion with an auditable archive lifecycle.
- Let administrators view revision history and restore a prior configuration safely.
- Detect stale admin edits without breaking existing API clients immediately.

## Non-Goals

- Scheduled rule activation or approval workflows.
- Multiple concurrent published versions of one rule.
- RBAC beyond the current admin role.
- Cross-rule branching, merging, or draft collaboration.
- Re-evaluating historical events automatically after a restore.
- Broker replay/backfill; that remains a later roadmap item.

## Behavioral Decisions

1. A new rule starts at revision `1` and records a `CREATED` history entry.
2. An effective configuration update creates revision `n + 1`; a no-op PUT does not create history noise.
3. Activate and deactivate operations create `ACTIVATED` or `DEACTIVATED` revisions.
4. `DELETE /api/rules/{id}` becomes a soft archive: the rule is made inactive, receives `archivedAt`, and records `ARCHIVED`.
5. Archived rules are excluded from ingestion and the default rule list but remain queryable through the admin API.
6. Restoring an old snapshot creates a new revision; it never rewrites or deletes existing history.
7. Restoring configuration does not reprocess old events and starts with independent aggregate and trigger state.
8. An already-queued webhook continues using its immutable destination, payload, cooldown, trigger mode, and rule revision snapshot.
9. Legacy rules and their aggregate/trigger state may be removed; any preserved outbox history is labeled revision `1` for compatibility.

## Legacy-Data Decision

The user confirmed that rules created before the newer CEP and webhook features did not need to be preserved. Before implementation, the five legacy rules and their 13 rule-event plus 2 trigger-state rows were removed after row-count inspection. The 104 accepted events, 5 idempotency records, and 3 completed webhook-delivery records were preserved.

## Data Model

### `rules`

Add:

- `revision` — positive integer, current domain revision, default `1`.
- `archived_at` — nullable timestamp.

Keep the stable rule ID. The `rules` row remains the current projection used by event evaluation and the existing CRUD API.

### `rule_revisions`

Add an immutable `RuleRevision` entity/table:

- `id` — generated primary key.
- `rule_id` — scalar rule ID, not a foreign key that disappears with lifecycle changes.
- `revision` — positive integer.
- `change_type` — `CREATED`, `UPDATED`, `ACTIVATED`, `DEACTIVATED`, `ARCHIVED`, `UNARCHIVED`, or `RESTORED`.
- `actor` — authenticated admin username, with `system` as a controlled fallback.
- `changed_at` — UTC timestamp.
- `restored_from_revision` — nullable source revision.
- `snapshot_schema_version` — starts at `1`.
- `snapshot` — immutable JSONB containing all normalized rule fields plus active/archive state.

Constraints and indexes:

- Unique `(rule_id, revision)`.
- Index `(rule_id, changed_at desc)`.

### Revision-scoped runtime state

Add `rule_revision` to:

- `rule_events`.
- `rule_action_state`.
- `rule_action_windows`.
- `webhook_outbox` as an immutable snapshot field.

Update indexes and uniqueness:

- Aggregate lookups: `(rule_id, rule_revision, group_key, occurred_at)`.
- Cooldown/edge state: unique `(rule_id, rule_revision, scope_key)`.
- Window state: unique `(rule_id, rule_revision, scope_key, window_start)`.

Include `revision=<n>` in all webhook deduplication-key formats. Worker finalization must find action/window state using both rule ID and the outbox revision so a delivery from an older revision cannot mutate the current revision's state.

## Backend Design

### Transactional mutation service

Introduce `RuleLifecycleService` and move all rule mutations out of the controller. Each operation must run in one transaction and:

1. Lock the current rule row for update when it already exists.
2. Validate the expected revision when supplied.
3. Normalize and compare the requested configuration.
4. Apply the change and increment the revision only when state changed.
5. Save the immutable post-change snapshot and actor in `rule_revisions`.
6. Return the current `RuleResponse`.

This ensures the current projection and audit record cannot diverge.

### Evaluation changes

- Persist the current rule revision on every `RuleEvent`.
- Pass revision to every aggregate repository query.
- Scope cooldown, edge, and once-per-window reads/writes by revision.
- Snapshot revision on every outbox row and worker delivery attempt.
- Keep rule testing side-effect free; testing a current rule uses its revision for historical aggregate reads but does not persist any row.
- Decide explicitly in the test API whether a historical revision is testable. The recommended first slice tests only the current revision; historical testing can follow later.

### Concurrency control

- Add `revision` to `RuleResponse` and return an `ETag` based on it for single-rule reads and mutations.
- The frontend sends `If-Match` for update, toggle, archive, unarchive, and restore.
- A stale revision returns `409 Conflict` with a clear reload message.
- For backward compatibility, accept a missing `If-Match` during the first release, while the bundled frontend always supplies it.

### Admin API

Keep existing routes and add:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/rules?includeArchived=false` | List current rules; archived rules are opt-in. |
| `GET` | `/api/rules/{id}/revisions` | Paginated revision summaries. |
| `GET` | `/api/rules/{id}/revisions/{revision}` | Full immutable snapshot. |
| `POST` | `/api/rules/{id}/revisions/{revision}/restore` | Apply a historical snapshot as a new revision. |
| `POST` | `/api/rules/{id}/unarchive` | Return an archived rule as inactive and record `UNARCHIVED`. |

Restore should reject invalid snapshots and stale `If-Match` values. It should default the restored rule to inactive when the source revision was active, requiring an explicit activation after review.

## Frontend Plan

- Add `revision` and `archivedAt` to the `Rule` type and define revision summary/detail types.
- Send `If-Match` on mutations and show a reload prompt for stale-edit conflicts.
- Replace the destructive Delete action with Archive and add a confirmation dialog.
- Add a `Show archived` filter and an Unarchive action to the Rules page.
- Add `/rules/[id]/history` with:
  - revision, operation, actor, and timestamp;
  - changed-field summary;
  - full snapshot detail;
  - restore action with confirmation.
- Link History from the rule row and edit page.
- After restore, invalidate current rule, list, and revision queries and route to the edit page for review.

## Migration and Compatibility

Use the explicit clean-boundary migration at `tengen/src/main/resources/db/rule-versioning.sql` because the repository currently relies on `spring.jpa.hibernate.ddl-auto=update`:

1. Stop rule mutations and the webhook worker for the migration window.
2. Report counts for legacy `rules`, `rule_events`, `rule_action_state`, and `rule_action_windows` rows.
3. Delete legacy rule-owned aggregate/trigger state in foreign-key-safe order, then delete the legacy rules.
4. Preserve accepted `events` and completed webhook-delivery history. Existing outbox rows receive revision `1`; active rows must either finish before cleanup or be handled under a separately approved delivery decision.
5. Create `rule_revisions`, add the revision columns, revised indexes, and unique constraints.
6. Start all newly created rules at revision `1`; do not create synthetic history for deleted legacy rules.
7. Verify constraints and row counts before restarting the application and worker.

The implementation must provide an idempotent PostgreSQL migration script and document how it is applied. Do not rely only on Hibernate to replace existing unique constraints.

Existing response fields remain unchanged; new fields are additive. Preserved webhook outbox rows use revision `1` and retain their original payload/destination behavior.

## Implementation Sequence

### Slice 1 — correctness and persistence

1. Add rule revision fields, lifecycle enums/entities, repositories, and migration.
2. Add `RuleLifecycleService` for create/update/toggle/archive with atomic snapshots.
3. Scope aggregate rows and queries by revision.
4. Scope webhook state, outbox snapshots, deduplication, and worker finalization by revision.
5. Add stale-write handling and revision fields to existing API responses.

### Slice 2 — history and restore API

1. Add paginated history and detail DTOs/endpoints.
2. Add restore and unarchive operations through `RuleLifecycleService`.
3. Cover snapshot schema validation and conflict responses.

### Slice 3 — admin console

1. Add archive/unarchive and archived filtering.
2. Add history list, snapshot comparison, and restore confirmation.
3. Add stale-edit recovery messaging.
4. Update README and the CEP roadmap status only after verification.

## Verification Plan

### Backend unit coverage

- Create produces revision `1` and one `CREATED` snapshot.
- Effective update increments once; no-op update does not.
- Toggle, archive, unarchive, and restore record the correct operation and actor.
- Restore creates a new revision without mutating the source snapshot.
- Stale `If-Match` is rejected.
- Snapshot serialization preserves every normalized rule field.
- Outbox deduplication keys differ between revisions.
- An old outbox completion cannot finalize current-revision trigger state.

### Database/integration coverage

- Existing rows backfill to revision `1` without data loss.
- Aggregate events from revision `1` do not contribute to revision `2`.
- Cooldown, edge, and once-per-window state are independent across revisions and groups.
- Archived rules do not evaluate, while queued deliveries still complete.
- Concurrent updates produce one winner and one conflict.
- History pagination and restore endpoints enforce JWT admin access.

Per `AGENTS.md`, ask the user before creating or running any Spring Boot integration test. No integration tests are authorized by this planning task.

### Frontend verification

- Run the existing lint/build checks for the Next.js app.
- Manually verify history navigation, archive filtering, restore confirmation, and stale-edit recovery.
- Verify responsive layouts for the Rules and History pages.

## Risks and Mitigations

- **Legacy cleanup deleting more than intended:** show exact row counts first, delete only rule-owned state and rules, and preserve events/delivery history unless separately approved.
- **Old deliveries touching new state:** persist revision on the outbox and include it in worker finalization lookups.
- **Aggregate behavior changing after edit:** this is intentional; document that every revision starts a fresh logical window.
- **Restore surprise:** restore as inactive and require explicit activation.
- **Audit gaps from direct repository writes:** route production mutations through `RuleLifecycleService`; test fixtures may continue using repositories directly.
- **History snapshot evolution:** include a snapshot schema version and map snapshots through dedicated DTO code.

## Acceptance Criteria

The feature is complete when an admin can inspect every rule lifecycle change, view immutable snapshots, archive or restore a rule, and observe that a newly revised rule does not reuse aggregate or webhook trigger state from earlier revisions. Queued deliveries from older revisions must still complete without changing current-revision state, and stale admin updates must be rejected safely.
