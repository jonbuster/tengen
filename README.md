<p align="center">
  <img src="assets/tengen-logo-1.png" alt="Tengen logo" width="240">
</p>

# Tengen

Tengen is a complex event processing web app for ingesting business events, evaluating configurable rules, and triggering automated webhook actions.

- **Payment fraud detection** — Flag unusually large payments or repeated payment failures and notify a fraud service.
- **Login security monitoring** — Detect multiple failed login attempts for the same user, account, or device within a time window.
- **Order workflow tracking** — Confirm that events such as order placement, payment, and fulfillment happen in the expected sequence.
- **Missing-event detection** — Identify when an expected event, such as a payment confirmation or delivery update, does not arrive before a deadline.
- **Operational alerts** — Monitor business activity and send webhook notifications when important thresholds or patterns are reached.

For the full guide, see the [Tengen documentation](https://tengen-gate.netlify.app/).
For AI-assisted queries, use the [Tengen LLM reference](documentation/tengen-llm.txt).

## Quick Start

Requirements: Docker, Java 21, Node.js, and npm.

Start PostgreSQL and RabbitMQ:

```bash
docker compose -f tengen/docker-compose.yml --profile rabbitmq up -d db rabbitmq
```

Start the backend in one terminal:

```bash
cd tengen
./mvnw spring-boot:run
```

Start the frontend in another terminal:

```bash
cd frontend
npm ci
npm run dev
```

Open http://localhost:3000.

Local admin credentials: `admin` / `admin`

## Screenshots

![Deliveries, replay, RabbitMQ connector, and API keys](<assets/ChatGPT Image Aug 4, 2026, 11_27_23 PM.png>)

![Rules, rule testing, Event Explorer, and settings](<assets/ChatGPT Image Aug 4, 2026, 11_29_19 PM.png>)

![Rule creation, test results, event details, and delivery details](<assets/ChatGPT Image Aug 4, 2026, 11_34_41 PM.png>)

![Replay details, RabbitMQ configuration, replay history, and login](<assets/ChatGPT Image Aug 4, 2026, 11_37_27 PM.png>)

## Features

- **HTTP event ingestion** — Accept JSON events through the REST API using scoped API keys. Useful for application and service integrations.
- **RabbitMQ event ingestion** — Consume events from a durable RabbitMQ queue through the admin-managed connector. Useful for asynchronous and broker-based workloads.
- **Condition rules** — Match individual events with configurable expressions for alerts and validation.
- **Aggregate rules** — Evaluate counts, sums, averages, minimums, and maximums over time windows.
- **Sequence rules** — Detect ordered multi-event behavior with optional correlation keys and time windows.
- **Absence rules** — Trigger when an expected follow-up event does not arrive before a deadline.
- **Grouping** — Maintain independent rule state per user, account, device, or another business key.
- **Trigger controls** — Configure every-match, rising-edge, once-per-window, and cooldown behavior.
- **Webhook actions** — Deliver actions asynchronously with retries, dead-letter handling, delivery history, and request signing.
- **Rule management and testing** — Create, edit, enable, archive, version, restore, and test rules from the admin console.
- **API key management** — Create scoped ingestion keys and safely retry events with idempotency protection.
- **Event Explorer** — Search accepted events, inspect payloads, and trace rule matches and webhook actions.
- **Delivery history and retries** — Inspect webhook attempts and retry failed deliveries.
- **Replay and backfill analysis** — Evaluate historical events against rule revisions without changing live state or sending webhooks.
- **Event-time processing** — Handle out-of-order events with watermarks and allowed-lateness controls.
- **Operational visibility** — Expose health checks and Prometheus metrics for deployment monitoring.

## Technology

| Area | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1, Spring Security, Spring Data JPA |
| Frontend | Next.js 15, React 19, MUI 6, TanStack Query, Axios |
| Database | PostgreSQL 17, Flyway |
| Messaging | RabbitMQ 4 |
| Rule engine | Aviator 5.4.3 |
| Authentication | JJWT 0.12.6, httpOnly cookies, hashed API keys |
| Testing and observability | Testcontainers, Vitest, Playwright, Prometheus |

### Production

Set production values in an untracked `.env` file or a secret manager:

```dotenv
SPRING_PROFILES_ACTIVE=prod
ADMIN_PASSWORD=replace-with-a-strong-password
JWT_SECRET=replace-with-a-random-secret
WEBHOOK_SIGNING_SECRET=replace-with-a-random-secret
CORS_ALLOWED_ORIGINS=https://your-domain.example
```

Build and start the production stack:

```bash
docker compose --env-file .env -f tengen/docker-compose.yml up --build -d
```

Add `--profile rabbitmq` when RabbitMQ is part of the deployment.

## License

Tengen is released under the [MIT License](LICENSE).
