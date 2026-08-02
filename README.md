# Tengen — Complex Event Processing Webapp

Tengen is a rule-driven Complex Event Processing web application for ingesting JSON events, evaluating declarative conditions and time-windowed aggregates, grouping events by business keys, and triggering actions such as webhooks.

The backend is built with **Spring Boot 4.1**, **Java 21**, **Spring Data JPA**, and **PostgreSQL 17**. The administration console is built with **Next.js 15**, **React 19**, **MUI 6**, and **TanStack Query**. Rules use **Aviator** expressions for condition evaluation.

Its architecture applies core CEP patterns found in platforms such as Esper, Siddhi, and Apache Flink, while providing a focused REST API and web-based administration console for rules, testing, event ingestion, and API keys.

## Features

- **Rule Engine** — define CONDITION and AGGREGATE rules with Aviator expressions
- **Windowed Aggregates** — `COUNT`, `SUM`, `AVG`, `MIN`, and `MAX` with event-time boundaries
- **Keyed Aggregates** — maintain independent windows using fields such as `data.userId`
- **Webhook Actions** — synchronous best-effort delivery with up to three attempts and short backoff
- **Webhook Cooldown** — durable global and keyed cooldown state; suppressed matches remain visible in `suppressedRules`
- **Event Ingestion** — POST events to `/api/events` with an `X-API-Key` header
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
- A matched rule remains matched when its webhook is suppressed, and the response identifies it through `suppressedRules`.

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
  - `/api/events` — event ingestion, with optional `X-API-Key` association
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
3. Ingest events with `X-API-Key`:

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -H "X-API-Key: <your-raw-key>" \
  -d '{"type":"transaction","source":"payment-api","data":{"amount":2500,"country":"PH"}}'
```

Keys are hashed at rest, shown only once at creation, associated with ingested events, and can be revoked immediately.

## CEP Roadmap

Implemented foundations include event ingestion, configurable rule evaluation, keyed aggregates, rule testing, synchronous webhook actions, and durable webhook cooldown handling.

Planned CEP capabilities include:

- EDGE and once-per-window trigger modes
- Event idempotency keys
- Transactional outbox and asynchronous delivery
- Rule versioning and audit history
- Sequence and absence patterns
- Watermarks and allowed lateness
- Broker connectors and replay/backfill

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file for details.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
