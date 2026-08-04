# Watermarks and Allowed Lateness Plan

## Summary

- Before changing application code, create `plans/2026-08-03-2243-watermarks-allowed-lateness-plan.md` containing this plan.
- Add durable watermarks scoped by event `source + type`.
- Use a global `INGESTION_ALLOWED_LATENESS_SECONDS` setting, defaulting to `300`.
- Persist all events, but skip rule evaluation and actions for events whose windows have closed.
- Keep Run Test independent from production watermarks.

## Implementation Changes

- Add Flyway V6 with:
  - `event_stream_watermarks`, uniquely keyed by `source + event_type`.
  - Durable `max_occurred_at`, monotonic `watermark_at`, and audit timestamps.
  - Nullable `event_time_status` and `watermark_at_decision` columns on `events`.
  - Status constraint for `ON_TIME`, `LATE_ACCEPTED`, and `TOO_LATE`.
  - An Event Explorer filtering index; historical events remain null and are not reconstructed.
- Add a transactional watermark service that:
  - Creates missing stream state safely, then locks the stream row during classification.
  - Treats the first event as `ON_TIME`.
  - Calculates the effective prior watermark as the later of the stored watermark and `max_occurred_at - allowed lateness`.
  - Classifies `occurredAt <= watermark` as `TOO_LATE`, `watermark < occurredAt < maxOccurredAt` as `LATE_ACCEPTED`, and newer/equal-max events as `ON_TIME`.
  - Advances maximum event time and watermark monotonically; increasing the configured grace period never reopens closed time.
- Integrate classification after authorization/idempotency reservation and before rule evaluation:
  - `ON_TIME` and `LATE_ACCEPTED` follow the existing pipeline unchanged.
  - `TOO_LATE` is saved with a zero-count processing trace but creates no rule outcomes, aggregate/sequence state, webhook outbox rows, cooldown reservations, or EDGE resets.
  - Watermark and event changes share the ingestion transaction and roll back together.
  - Completed idempotency replays return their stored response without reclassification or watermark advancement.
- Add event-time counters labeled by the three statuses.
- Document the new environment variable in application properties, Docker Compose, and README; update the CEP roadmap when implemented.

## Public Interfaces and UI

- Add `eventTimeStatus` to both full and compact successful producer responses.
- Keep HTTP `200` and `status: "accepted"` for too-late events; return `matched: false` with empty rule/action results.
- Existing stored idempotency responses remain byte-equivalent and may omit the new additive field.
- Extend Event History summaries with nullable `eventTimeStatus` and `watermarkAtDecision`.
- Add an optional `eventTimeStatus` filter to `GET /api/event-history`.
- Update Event Explorer with:
  - Timing status chips and filtering.
  - A legacy/unknown state for pre-migration events.
  - A detail warning explaining that a too-late event was retained but not evaluated.
  - The watermark used for the decision.
- Do not add an operational Settings UI or a separate watermark administration endpoint in this slice.

## Test Plan

- Unit-test first-event handling, exact-boundary rejection, allowed out-of-order events, newer events, stream isolation, and monotonic behavior after configuration changes.
- Verify compact and full response projection includes the event-time status.
- Add and run the approved Spring Boot integration coverage for:
  - Durable watermark creation and advancement.
  - Independent source/type streams.
  - Concurrent ingestion without watermark regression.
  - Late-accepted aggregate participation.
  - Too-late persistence without rule, sequence, trigger, or webhook mutations.
  - Idempotent replay without a second advancement.
  - Event History status filtering and legacy-null compatibility.
- Add frontend tests for list chips, filters, legacy display, and the too-late detail warning.
- Run focused backend tests first, then backend test suite and frontend lint, tests, and build.

## Assumptions and Boundaries

- Allowed lateness defaults to five minutes and must be non-negative.
- Watermarks advance only when new events are ingested; scheduled idle-stream advancement belongs to the future absence-rule slice.
- No buffering, correction, retraction, or retroactive sequence reordering is introduced.
- Late-accepted events use existing processing semantics; previously missed sequence completions are not replayed.
- Broker ingestion, historical backfill, absence patterns, and per-stream/per-rule lateness policies remain out of scope.
