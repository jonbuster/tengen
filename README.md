# Tengen — Complex Event Processor & Admin Console

Rule-based event processing with a **Spring Boot 4 (Java 21)** API and a **Next.js 15 + MUI 6** admin SPA.

Ingest events via a simple REST endpoint, evaluate them against Aviator-powered rules, and manage everything from a modern web console.

## Features

- **Rule Engine** — define event rules with Aviator expressions; supports aggregates, webhook actions, and per-rule evaluation
- **Event Ingestion** — POST events to `/api/events` with a scoped `X-API-Key` header
- **Admin Console** — full CRUD for rules, a live rule tester, and API key management
- **JWT Auth** — access + refresh tokens stored in **httpOnly cookies**; the browser never sees a JWT
- **Secure by Default** — server-side token refresh, BCrypt-hashed admin credentials, revocable API keys

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
  - `/api/events` — event ingestion, authenticated via `X-API-Key`
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

Keys can be scoped to allowed event types / sources and revoked — revocation takes effect immediately.

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file for details.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
