<p align="center">
  <img src="assets/tengen-logo-1.png" alt="Tengen logo" width="240">
</p>

# Tengen — Complex Event Processing Webapp

Tengen helps teams turn incoming business events into useful actions. You can define rules such as:

- Alert when a payment exceeds a certain amount.
- Detect multiple failed login attempts from the same user.
- Count transactions from a customer within a five-minute window.
- Group activity by user, account, or device.
- Send a webhook when suspicious or important activity is detected.

Tengen receives JSON events, evaluates them against your rules, tracks activity over time, and reports matching events or triggers automated actions. It is designed to help teams build reliable event-driven workflows without writing custom processing logic for every use case.

The project includes a REST API for event ingestion and a web-based administration console for creating rules, testing event behavior, and managing API keys.

## Technical Overview

The backend is built with **Spring Boot 4.1**, **Java 21**, **Spring Data JPA**, and **PostgreSQL 17**. The administration console uses **Next.js 15**, **React 19**, **MUI 6**, and **TanStack Query**. Rules use **Aviator** expressions for condition evaluation.

Its architecture applies core CEP patterns found in platforms such as Esper, Siddhi, and Apache Flink, while providing a focused REST API and web-based administration console for rules, testing, event ingestion, and API keys.

## Features

- **Rule Engine** — define CONDITION and AGGREGATE rules with Aviator expressions
- **Windowed Aggregates** — `COUNT`, `SUM`, `AVG`, `MIN`, and `MAX` with event-time boundaries
- **Keyed Aggregates** — maintain independent windows using fields such as `data.userId`
- **Webhook Actions** — synchronous best-effort delivery with up to three attempts and short backoff
- **Webhook Cooldown** — durable global and keyed cooldown state; suppressed matches remain visible in `suppressedRules`
- **Webhook Trigger Modes** — `EVERY_MATCH`, durable `EDGE` delivery on false-to-true transitions, or durable `ONCE_PER_WINDOW` delivery for aggregate rules
- **Event Ingestion** — POST events to `/api/events` with a required `X-API-Key` header
- **Event Idempotency** — optional API-key-scoped `Idempotency-Key` support prevents duplicate persistence, rule evaluation, aggregate contribution, and webhook delivery during retries
- **Admin Console** — full CRUD for rules, a visual condition builder, rule tester, and API key management
- **JWT Auth** — access + refresh tokens stored in **httpOnly cookies**; the browser never sees a JWT
- **Secure by Default** — server-side token refresh, BCrypt-hashed admin credentials, hashed and revocable API keys

## CEP Capabilities

- Rules can match on event type, source, and Aviator conditions.
- Aggregate rules support global or grouped windows with `COUNT`, `SUM`, `AVG`, `MIN`, and `MAX`.
- Aggregate windows use event time with an exclusive lower boundary and inclusive current-event boundary.
- Rule testing includes the candidate event in aggregate calculations without persisting it or triggering webhooks.
- Webhook cooldowns use processing time and are scoped by rule for global rules or by rule plus group key for keyed rules.
- Successful webhook delivery starts or refreshes cooldown; failed delivery leaves the cooldown available for retry.
- `EDGE` webhook triggers fire only when a rule changes from non-matching to matching; a failed delivery remains retryable.
- `ONCE_PER_WINDOW` webhook triggers fire once per fixed event-time bucket of the aggregate rule's `windowSeconds`; failed delivery remains retryable and keyed rules are scoped independently.
- Trigger state is scoped by rule and aggregate group key, and rule testing does not change it.
- A matched rule remains matched when its webhook is suppressed, and the response identifies it through `suppressedRules`.
- Event idempotency keys are scoped per API key and use a unique request fingerprint; an equivalent retry replays the original response, while a changed payload returns `409 Conflict`.
- Different legitimate events, including events with identical data but different timestamps, must use different idempotency keys.

## Tech Stack

| Layer | Technology |
| --- | --- |
| Backend | Spring Boot 4.1, Java 21, Spring Security, Spring Data JPA |
| Rules | Aviator (5.4.3) |
| Frontend | Next.js 15, React 19, MUI 6, TanStack Query, Axios |
| Database | PostgreSQL 17 |
| Auth | JJWT (0.12.6) — httpOnly cookies |

## Architecture

```
┌────────────────┐     ┌─────────────────────┐     ┌─────────────────┐
│  Next.js SPA   │────▶│  Next.js Proxy      │────▶│  Spring Boot    │
│  (:3000)       │     │  /api/proxy/[...]   │     │  API (:8080)    │
└────────────────┘     └─────────────────────┘     └────────┬────────┘
                                                            │
                                                  ┌─────────▼─────────┐
                                                  │  PostgreSQL 17    │
                                                  │  (:5432)          │
                                                  └───────────────────┘
```

- **Frontend** (`frontend/`) — Next.js 15 SPA. All API calls go through the route handler [`/api/proxy/[...path]`](frontend/src/app/api/proxy/[...path]/route.ts), which attaches `Authorization: Bearer` server-side and auto-refreshes on 401.
- **Backend** (`tengen/`) — Spring Boot REST API.
  - `/api/auth/login`, `/api/auth/refresh` — JWT auth (access 15 min, refresh 7 days)
  - `/api/rules/**`, `/api/keys/**` — JWT-protected admin API
  - `/api/events` — API-key-only event ingestion
- **Database** — PostgreSQL 17, managed by Docker Compose.

## Quick Start (Development)

```bash
# Terminal 1 — database + backend (devtools auto-restart)
docker compose -f tengen/docker-compose.yml up -d db
cd tengen && ./mvnw spring-boot:run

# Terminal 2 — frontend (hot reload)
cd frontend && npm install
npm run dev
```

- Frontend: http://localhost:3000 — login `admin` / `admin` (default)
- Backend: http://localhost:8080

> ⚠️ Change `ADMIN_PASSWORD` and `JWT_SECRET` before any production use.

## Production (Docker Compose)

Builds and runs all three services:

```bash
docker compose -f tengen/docker-compose.yml up --build -d
```

| Service | Port | Notes |
| --- | --- | --- |
| `db` | 5432 | PostgreSQL 17 |
| `app` | 8080 | Spring Boot API |
| `frontend` | 3000 | Next.js SPA (proxy targets `http://app:8080` in the compose network) |

### Rerun only the frontend

```bash
docker compose -f tengen/docker-compose.yml up -d --build frontend
```

## Environment Variables

### Backend (`tengen/src/main/resources/application.properties`)

| Variable | Default | Purpose |
| --- | --- | --- |
| `JWT_SECRET` | `dev-secret-change-me-please-32-bytes-min` | HMAC key for JWT signing — **must be ≥ 32 bytes; change in production** |
| `JWT_ACCESS_TTL_MINUTES` | `15` | Access token lifetime |
| `JWT_REFRESH_TTL_DAYS` | `7` | Refresh token lifetime |
| `ADMIN_USER` | `admin` | Admin console username (BCrypt-hashed at startup) |
| `ADMIN_PASSWORD` | `admin` | Admin console password — **change in production** |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated browser origins allowed for the SPA |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/tengen` / `tengen` / `tengen` | PostgreSQL connection |

### Frontend

| Variable | Default | Purpose |
| --- | --- | --- |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Backend base URL used by the proxy route handler (baked at build time in Docker) |

Example dev setup with a custom backend origin:

```bash
NEXT_PUBLIC_API_URL=http://localhost:8080 npm run dev
```

## Ingesting Events

1. Log in to the admin console → **API Keys**.
2. Create a key — the raw value (prefix `tg_...`) is shown **once**; store it server-side.
3. Ingest events with the required `X-API-Key`:

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -H "X-API-Key: <your-raw-key>" \
  -d '{"type":"transaction","source":"payment-api","data":{"amount":2500,"country":"PH"}}'
```

Keys are hashed at rest, shown only once at creation, scoped to allowed event types and sources, associated with ingested events, and can be revoked immediately.
Requests without a valid key return `401`; a valid key that is not allowed to publish the event type or source returns `403`.

### Retrying Event Requests

Producers should send an `Idempotency-Key` header for reliable retries. Create the key once for the logical event and persist it with the producer's outbox or message record. Reuse the same key when retrying after a timeout:

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -H "X-API-Key: <your-raw-key>" \
  -H "Idempotency-Key: payment-123" \
  -d '{"type":"payment","source":"billing","timestamp":"2026-08-02T10:00:00Z","data":{"amount":100}}'
```

The key is scoped to the API key. A retry with the same key and equivalent payload returns the original response without persisting or evaluating the event again. Reusing a key with a different payload returns `409 Conflict`. Different legitimate events, including events with identical data but different timestamps, must use different keys.

The header is optional for backward compatibility; requests without it continue to be processed normally.

Idempotency records are stored durably in PostgreSQL and the original response is saved for replay. The current API does not add a separate replay indicator to the response; the response body remains equivalent to the first successful request.

## CEP Roadmap

Implemented foundations include event ingestion, configurable rule evaluation, keyed aggregates, rule testing, synchronous webhook actions, durable webhook cooldown and trigger-window handling, and optional event idempotency keys.

The next approved implementation sequence replaces synchronous webhook delivery with a durable, observable pipeline:

Planning for all three slices is complete; code implementation remains pending.

1. **Durable webhook outbox** — commit webhook delivery intent in the same transaction as the accepted event.
2. **Background delivery worker** — claim queued deliveries safely, retry transient failures, and dead-letter exhausted work.
3. **Delivery history** — provide admin APIs and a console page for delivery status, diagnostics, and controlled manual retry.

Detailed plans:

- [`plans/durable-webhook-outbox-plan.md`](plans/durable-webhook-outbox-plan.md)
- [`plans/webhook-delivery-worker-plan.md`](plans/webhook-delivery-worker-plan.md)
- [`plans/webhook-delivery-history-plan.md`](plans/webhook-delivery-history-plan.md)

Later CEP capabilities include:

- Optional event response detail levels and an explicit idempotency replay response header
- Rule versioning and audit history
- Sequence and absence patterns
- Watermarks and allowed lateness
- Broker connectors and replay/backfill

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file for details.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
