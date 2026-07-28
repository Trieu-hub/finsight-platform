# Vernfy

[![CI](https://github.com/Trieu-hub/finsight-platform/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Trieu-hub/finsight-platform/actions/workflows/ci.yml)

**Financial Intelligence & Risk Monitoring Platform** — a Spring Boot 4 / Java 21
microservice monorepo.

Vernfy is an event-driven finance platform: users record transactions and budgets over a
REST API, and an asynchronous Kafka backbone feeds a **risk-intelligence** service that
derives risk alerts, behavioral insights, and anomalies from the activity. Each service owns
its own database and the only synchronous fan-out is a read-only BFF; all other cross-service
coupling is asynchronous over Kafka.

> **Status:** working MVP with a rule-based intelligence layer. The intelligence is
> **rule-based, not ML**. See [Roadmap / not yet built](#roadmap--not-yet-built) for what is
> intentionally absent, and [`project-status.md`](project-status.md) for a detailed breakdown.

## Documentation

| Doc | Contents |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | Service boundaries, request/event flow, diagrams |
| [`docs/event-catalog.md`](docs/event-catalog.md) | Every Kafka event: producer, consumers, payloads |
| [`docs/intelligence.md`](docs/intelligence.md) | Risk rules, insights, anomalies — triggers & metrics |
| [`docs/runbook.md`](docs/runbook.md) | Startup, compose workflow, Kafka/Prometheus/Grafana verification, troubleshooting |
| [`project-status.md`](project-status.md) | Phase-by-phase completion and roadmap |
| [`docs/ADR-0004-budget-utilization-via-events.md`](docs/ADR-0004-budget-utilization-via-events.md) | Why budget utilization is event-driven (and its accepted drift) |
| [`docs/deploy.md`](docs/deploy.md) | VPS deploy (Caddy/TLS, SOPS-encrypted secrets, nightly backups) + CI / ephemeral staging / gated CD (§9) |
| [`load-test/`](load-test/) | k6 smoke + load scripts, run by the staging workflow (guarded so they never hit prod) |

## Tech stack

- **Java 21**, **Spring Boot 4.0.6**, Spring Security, Spring Data JPA
- **MySQL 8** — one shared instance, one logical database per service
- **Redis** — used by auth-service (refresh tokens + brute-force lockout)
- **Kafka** (single-node KRaft broker) — asynchronous event backbone
- **Flyway** — schema ownership (`ddl-auto: validate`)
- **JWT** (RS256) — signed by auth-service, the only holder of the private key; every service
  verifies with the public key alone, discovered by `kid` from a published JWK Set
- **springdoc / OpenAPI** — API docs on the user-facing REST services
- **Micrometer + Prometheus + Grafana** — metrics and dashboards
- **Docker / Docker Compose**, **GitHub Actions** (CI), **Testcontainers** (integration tests)
- **React 19 + TypeScript + Vite + TailwindCSS** — single-page web client (see [Web frontend](#web-frontend))

## Architecture summary

```
                 ┌──────────────┐
   Client ─────▶ │ api-gateway  │  edge JWT validation + routing
                 │   :8080      │
                 └──────┬───────┘
        ┌───────────┬───┴────┬────────────┬─────────────┐
        ▼           ▼        ▼            ▼             ▼
   auth :8081  user :8082  tx :8083   budget :8084  dashboard :8085
   auth_db     user_db     transaction_db budget_db   (no DB, BFF)
        │                     │            │   ▲          │ calls user/tx/budget
        │                     │            │   │          ▼ (REST, relays JWT)
     (Redis)                  │            │   │
                              ▼            ▼   │
                       ┌───────────── Kafka ───────────────┐
                       │ finsight.transactions.created      │
                       │ finsight.budgets.changed           │
                       │ finsight.risk.detected             │
                       └───────────────┬───────────────────┘
                                       ▼
                                 risk-service :8086  ──▶ risk_db
                                 (risk rules · insights · anomalies)
                                       │ produces finsight.risk.detected
                                       ▼
                          notification-service :8087  ──▶ notification_db
                          (in-app alerts materialized from RiskDetected)

                          analytics-service :8088  ──▶ analytics_db
                          (per-month rollup from TransactionCreated; AI monthly summary)
```

- **Synchronous** (HTTP/REST): client → gateway → owning service; the dashboard BFF fans out
  to user/transaction/budget, relaying the caller's JWT (fail-fast). No other business service
  calls another at runtime.
- **Asynchronous** (Kafka): transaction-service produces `TransactionCreated`; budget-service,
  risk-service **and analytics-service** consume it; budget-service produces `BudgetChanged`
  (consumed by risk-service); risk-service produces `RiskDetected`, consumed by
  notification-service, which materializes per-user in-app notifications. analytics-service
  folds `TransactionCreated` into a per-month rollup read model.

Full diagrams (Mermaid) are in [`docs/architecture.md`](docs/architecture.md).

**Design rules** (enforced in code): no runtime cross-service calls except the dashboard BFF;
`userId` is read only from the JWT; Flyway owns every schema; every service validates the JWT
locally (the gateway stays removable); `risk-service` is internal (no JWT stack, not behind the
gateway).

## Service inventory

| Service | Port | Database | Inbound | Responsibility |
|---|---|---|---|---|
| `api-gateway` | 8080 | – | HTTP | Edge routing + JWT validation (RS256/issuer/audience) |
| `auth-service` | 8081 | `auth_db` | HTTP | Register, login, refresh, account lockout; Redis-backed tokens |
| `user-service` | 8082 | `user_db` | HTTP | User profile data |
| `transaction-service` | 8083 | `transaction_db` | HTTP | Transactions (INCOME/EXPENSE/TRANSFER), categories, wallets (accounts + balances), summaries; **produces** `TransactionCreated` |
| `budget-service` | 8084 | `budget_db` | HTTP, Kafka | Budget definitions + utilization; **consumes** `TransactionCreated`, **produces** `BudgetChanged` |
| `dashboard-service` | 8085 | _(none, BFF)_ | HTTP | Read-only aggregation over user/transaction/budget; relays JWT; fail-fast |
| `risk-service` | 8086 (internal) | `risk_db` | Kafka | Risk rules, behavioral insights, anomaly detection; **consumes** `TransactionCreated` + `BudgetChanged`, **produces** `RiskDetected`; read APIs. Port not host-published (SE-2) |
| `notification-service` | 8087 | `notification_db` | HTTP, Kafka | In-app notifications; **consumes** `RiskDetected`; user-scoped read/mark-read API; optional **LLM narrator** (OpenAI-compatible, Groq free tier by default, off unless configured) |
| `analytics-service` | 8088 | `analytics_db` | HTTP, Kafka | Per-user monthly **rollup read model**; **consumes** `TransactionCreated`; overview / categories / forecast APIs; optional **AI monthly summary** (OpenAI-compatible, Groq free tier by default, off unless configured) |

## Databases

One **MySQL 8** instance hosts seven logical databases (DB-per-service isolation):

| Database | Owner | Notable tables |
|---|---|---|
| `auth_db` | auth-service | users, roles, refresh-token records |
| `user_db` | user-service | user_profiles |
| `transaction_db` | transaction-service | transactions, categories |
| `budget_db` | budget-service | budgets (incl. `spent_amount`), `processed_events` (idempotency inbox) |
| `risk_db` | risk-service | `risk_alerts`, `observed_expenses`, `insights`, `budget_snapshots`, `anomalies` |
| `notification_db` | notification-service | `notifications`, `processed_events` (idempotency inbox) |
| `analytics_db` | analytics-service | `monthly_category_rollup`, `processed_events` (idempotency inbox) |

`dashboard-service` owns no database. **Redis** backs only auth-service.

## Kafka topics

Single-node KRaft broker; JSON without type headers; keyed by `userId`; at-least-once delivery
with idempotent consumers. Full payloads in [`docs/event-catalog.md`](docs/event-catalog.md).

| Topic | Producer | Consumer(s) |
|---|---|---|
| `finsight.transactions.created` | transaction-service | budget-service, risk-service, analytics-service |
| `finsight.budgets.changed` | budget-service | risk-service |
| `finsight.risk.detected` | risk-service | notification-service |

## Implemented intelligence

All in **risk-service**, derived from the `observed_expenses` read-model fed by the
`TransactionCreated` consumer — **no ML, no prediction**, simple counts/sums/averages only.
Triggers, severities, persistence, and metrics are detailed in
[`docs/intelligence.md`](docs/intelligence.md).

**Risk Monitoring** → persisted to `risk_alerts`, published as `RiskDetected`, exposed at
`GET /api/v1/risks`; metric `finsight.risk.events.detected{type,severity}`:

| Rule | Trigger | Severity |
|---|---|---|
| `HIGH_AMOUNT_EXPENSE` | A single EXPENSE ≥ 10,000,000 | HIGH |
| `RAPID_SPENDING` | 5th EXPENSE for a user within a 10-minute window | MEDIUM |
| `LARGE_DAILY_SPEND` | Daily EXPENSE total crosses above 20,000,000 | HIGH |

**Behavioral Insights** → persisted to `insights` (one per scope per month), exposed at
`GET /api/v1/insights`; metric `finsight.insights.generated{type}`:

| Insight | Trigger |
|---|---|
| `SPENDING_INCREASE` | Current-month expenses ≥ +30% vs previous month |
| `CATEGORY_SURGE` | Current-month category expenses ≥ +50% vs previous month |
| `BUDGET_RISK` | A matching budget's utilization exceeds 80% while its period is open |
| `LOW_SAVINGS_RATE` | Month with positive income where expenses reach ≥ 80% of income |

**Anomaly Detection** → persisted to `anomalies`, exposed at `GET /api/v1/anomalies`; metric
`finsight.anomalies.detected{type}`:

| Anomaly | Trigger |
|---|---|
| `UNUSUAL_TRANSACTION_AMOUNT` | An EXPENSE ≥ 3× the user's average historical expense, with ≥ 10 prior EXPENSE transactions |

> The risk-service read APIs (`/api/v1/risks`, `/api/v1/insights`, `/api/v1/anomalies`) are an
> internal/admin surface — unauthenticated by design, not behind the gateway, and **not published
> to the host** (reachable only on the compose network at `risk-service:8086`, SE-2).

## Observability stack

Every service exposes Micrometer metrics at `/actuator/prometheus` and liveness/readiness
probes at `/actuator/health/{liveness,readiness}`.

- **Prometheus** — <http://localhost:9090> — scrapes all nine services every 15s
  (`docker/prometheus/prometheus.yml`); check *Status → Targets*.
- **Grafana** — <http://localhost:3000> — auto-provisions the Prometheus datasource and four
  dashboards (folder **FinSight**, from `docker/grafana/provisioning/`):
  - **FinSight Platform Overview** — request rate, 5xx rate, p95 latency, JVM heap, GC, CPU.
  - **FinSight Event Pipeline** — budget consumer `processed` / `duplicate` / `ignored` / `failed`.
  - **FinSight Risk** — detected risks by type and severity.
  - **FinSight Consumer Lag** — Kafka consumer lag per service / group / partition.
- **Alertmanager** — <http://localhost:9093> — receives firing alerts from Prometheus. Rules in
  `docker/prometheus/alerts.yml`: service down, 5xx rate, JVM heap, Kafka consumer lag, dashboard
  circuit-breaker open. No delivery
  channel is wired by default (a Slack/email/webhook stub is in `docker/alertmanager/alertmanager.yml`).

> Dev-stack posture, on purpose: Grafana allows anonymous admin and the scrape endpoint is
> unauthenticated — acceptable on a local compose network, not a production posture.

### Dashboard screenshots

Live dashboards from the running stack (under [`docs/images/`](docs/images/)):

![Grafana — Platform Overview](docs/images/grafana-platform-overview.jpg)
![Grafana — Event Pipeline](docs/images/grafana-event-pipeline.jpg)
![Grafana — Risk](docs/images/grafana-risk.jpg)
![Grafana — Consumer Lag](docs/images/grafana-consumer-lag.jpg)

## Web frontend

A single-page React client (in [`web/`](web/)) consumes the platform's REST API through the
api-gateway. It is a thin presentation layer — all business logic, validation, and authorization
stay in the backend.

- **Vite + React 19 + TypeScript**, **React Router**, **Axios**, **TailwindCSS**.
- **JWT auth**: the token from `POST /api/v1/auth/login` is stored client-side and attached to
  every request by an Axios interceptor; a `401` clears it and redirects to `/login`. Protected
  routes are gated client-side for UX only — the backend remains the security boundary.
- **Pages**: Login / Register, Dashboard (income / expense / balance + recent activity + budget
  progress), Transactions (list + create, incl. wallet selection and wallet-to-wallet transfers),
  Budgets (list + utilization bars), Wallets (accounts with live balances, create / delete),
  Analytics (month-over-month overview, spend forecast, top movers, category breakdown, and an
  AI/template monthly summary — served by analytics-service), Admin console (RBAC user management,
  ROLE_ADMIN only).
- **Notification bell**: a header bell polls `GET /api/v1/notifications/unread-count`, shows an
  unread badge, and opens a dropdown of risk alerts (severity-coloured) with mark-read /
  mark-all-read — the in-app surface for what notification-service materializes from `RiskDetected`.
- **Dev proxy**: Vite forwards `/api` → `http://localhost:8080`, so the browser stays
  same-origin and no backend CORS configuration is needed (a reverse proxy plays this role in
  production).

```bash
npm install --prefix web
npm run dev --prefix web        # http://localhost:5173 (needs the stack running on :8080)
npm run build --prefix web      # type-check + production build to web/dist
```

## Local startup (Docker Compose)

The root `docker-compose.yml` builds all eight services and starts MySQL, Redis, Kafka,
Prometheus, and Grafana. auth-service holds the JWT signing key; the rest get the public key.

**1. Secrets (`.env`) — required first.** No secrets live in compose; they are interpolated
from a gitignored `.env`. Compose refuses to start (clear `set X in .env` message) if any are
missing.

```bash
cp .env.example .env
./scripts/gen-jwt-keys.sh          # prints an RS256 keypair; paste into JWT_PRIVATE_KEY / JWT_PUBLIC_KEY
# Then fill in: MYSQL_ROOT_PASSWORD and the five *_DB_PASSWORD values
# (AUTH/USER/TRANSACTION/BUDGET/RISK). Generation commands are in the file.
```

**2. Start the stack:**

```bash
docker compose up --build -d     # build images + start everything
docker compose ps                # watch readiness-gated startup
docker compose logs -f risk-service
docker compose down              # stop  (add -v to also drop MySQL/Prometheus/Grafana volumes)
```

**3. Verify health** (services are readiness-gated via healthchecks + `depends_on`):

```bash
# risk-service (8086) is not host-published (internal-only); check it via `docker compose ps`.
for p in 8080 8081 8082 8083 8084 8085; do
  curl -fsS http://localhost:$p/actuator/health/readiness && echo " <- $p OK"
done
```

On the **first** MySQL start, init scripts create the five databases and one least-privilege
user per service (`auth_user`, `user_user`, `transaction_user`, `budget_user`, `risk_user`) —
each service connects as its own user, never `root`. Kafka/MySQL/Redis ports are not published
to the host; see [`docs/runbook.md`](docs/runbook.md) for Kafka/Prometheus/Grafana verification
and troubleshooting.

> Init scripts run only against an empty data dir. If you have an existing `mysql_data` volume
> from before the risk-service database was added, recreate it with `docker compose down -v`.

### Run / test a single service

```bash
cd services/<service>
./mvnw spring-boot:run     # mvnw.cmd on Windows; needs a DB and (for JWT services) JWT_PUBLIC_KEY
./mvnw verify              # unit + Testcontainers integration tests (Docker required)
```

## Continuous Integration

GitHub Actions (`.github/workflows/ci.yml`) builds and tests every service on each
`pull_request` and on pushes to `main`. A single matrix job fans out across all **eight**
modules (`api-gateway`, `auth-service`, `user-service`, `transaction-service`, `budget-service`,
`dashboard-service`, `risk-service`):

- **JDK 21** (Temurin) with the Maven (`~/.m2`) cache enabled.
- Each module runs `mvn -B -ntp verify` — unit **and** Testcontainers integration tests in one
  pass (MySQL/Kafka containers via Testcontainers; the runner ships with Docker).
- `fail-fast` is off, so one run reports every failing service; failing modules upload their
  Surefire reports as artifacts.

**Ephemeral staging** (`.github/workflows/staging.yml`) — the single 8 GB prod VPS has no room for
a parallel stack, so "staging" is *ephemeral*: on PRs that touch `services/`, `docker-compose.yml`,
or `load-test/` (and nightly), it stands up the **whole compose stack** on a throwaway runner,
waits for the gateway to report healthy, runs the k6 **smoke** then **load** test
([`load-test/`](load-test/)), and tears it down. A failed smoke or a missed latency/error SLO fails
the job — the first time a PR is exercised **whole-stack** (gateway → services → Kafka), not just
per-service in isolation.

**Gated production deploy** (`.github/workflows/deploy-prod.yml`) — a **manual**,
environment-gated deploy (no auto-deploy on merge). You dispatch it and pick the ref; if the
`production` Environment has required reviewers, a second person approves. The runner then SSHes
into the VPS and runs the box's **own** deploy path (`git reset --hard <ref>` →
`scripts/prod-compose.sh up -d --build`) and gates on the gateway's in-network
`/actuator/health`. **CI never holds an application secret** — the SOPS age key lives only on the
box, which decrypts its own `secrets.env`. Setup + required secrets:
[`docs/deploy.md` §9](docs/deploy.md).

> There is no aggregator pom; the matrix is what builds "all services" in CI.

## End-to-end validation

The full event-driven path is implemented and traceable in the repo (code + config), and the
runtime is captured by the committed Grafana dashboard screenshots above.

| Stage | Status | Evidence in repo |
|---|---|---|
| Expense creation (REST) | ✅ implemented | `transaction-service` |
| Kafka event published (`TransactionCreated`) | ✅ implemented | `transaction-service` producer → `finsight.transactions.created` |
| Risk detection executed | ✅ implemented | `risk-service` rules ([`docs/intelligence.md`](docs/intelligence.md)) |
| Database updated | ✅ implemented | `risk_db.risk_alerts` (Flyway-owned) |
| Prometheus metrics updated | ✅ implemented | `/actuator/prometheus` · `finsight.risk.events.detected{type,severity}` |
| Grafana dashboard updated | ✅ provisioned | `docker/grafana/provisioning/` (4 dashboards) |
| CI pipeline passing | ✅ workflow + badge | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| Whole-stack E2E + load test on PR | ✅ workflow | [`.github/workflows/staging.yml`](.github/workflows/staging.yml) stands up the full stack + [`load-test/`](load-test/) k6 smoke/load |
| Runtime screenshots committed | ✅ committed | `docs/images/` (4 Grafana dashboards, embedded above) |

## Roadmap / not yet built

These are **absent from the codebase** — do not assume they exist:

- **gRPC** — no proto, no dependencies; all synchronous calls are REST.
- **External notification delivery** — notification-service creates **in-app** notifications
  from `RiskDetected`; email/push/webhook delivery is not built. (An optional **LLM message
  narrator** — OpenAI-compatible, default Groq free tier, off by default with a rule-based
  fallback — *is* built; see [Web frontend](#web-frontend) / `services/notification-service`.)
- **ML-based intelligence** — current rules are deterministic and threshold-based.
- **A managed secrets store and an orchestrated deployment target** (Kubernetes/ECS, Vault).
  The live demo runs Docker Compose on a single VPS behind Caddy with TLS, and secrets in a
  `chmod 600` `.env` — fine at hobby scale, not a secrets manager.
- **Security scanning** (SAST + dependency + secret) — not wired on this branch. (Load/performance
  testing and whole-stack E2E in CI, previously listed here, **are** now built — see
  [Continuous Integration](#continuous-integration).)

See [`project-status.md`](project-status.md) §5 for the prioritized roadmap.
