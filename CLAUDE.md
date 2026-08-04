# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

> Priority:
> 1. User request
> 2. This CLAUDE.md
> 3. Existing codebase conventions
>
> When these conflict, ask instead of assuming.

---

# AI Working Principles

These rules exist to reduce common LLM coding mistakes.

The goal is **correctness, maintainability, and minimal diffs**, not maximizing generated code.

---

## 1. Think Before Coding

Never guess.

Before implementing:

- State assumptions explicitly.
- If requirements are ambiguous, ask.
- If multiple valid implementations exist, present the options.
- If a significantly simpler solution exists, recommend it.
- If information is missing, stop and ask.

Never silently invent requirements.

---

## 2. Simplicity First

Write the smallest correct solution.

Avoid:

- speculative abstractions
- unnecessary interfaces
- future-proofing
- over-configurable code
- premature optimization

Don't introduce new frameworks or libraries unless explicitly requested.

If 50 lines solve the problem, never write 200.

---

## 3. Surgical Changes

Only modify code required for the task.

Do NOT:

- reformat unrelated files
- rename unrelated variables
- move code without reason
- refactor unrelated modules
- "clean up" nearby code

Do:

- remove imports created unused by YOUR changes
- keep existing coding style
- preserve blame history whenever possible

Every modified line should be directly traceable to the user's request.

---

## 4. Goal-Driven Execution

Convert vague tasks into measurable goals.

Examples:

"Fix bug"

↓

- reproduce
- fix
- verify

"Add validation"

↓

- write failing test
- implement validation
- ensure tests pass

For larger work, first produce a short plan:

1. ...
2. ...
3. ...

Then execute.

---

## 5. Verify Before Finishing

Before considering work complete:

- project compiles
- tests pass (when applicable)
- no obvious warnings introduced
- no unused imports
- no dead code created
- no unrelated files modified

Never claim something works without verification.

---

# Project Overview

Vernfy — Financial Intelligence & Risk Monitoring Platform.

Repo directory is `finsight`, GitHub repo is `finsight-platform`, product name is **Vernfy**.
All three names appear; none of them are interchangeable in code.

An **event-driven microservice monorepo**, not a monolith. Users record transactions and budgets
over REST; an asynchronous Kafka backbone feeds a risk-intelligence service that derives alerts,
insights, and anomalies. Each service owns its own database.

The intelligence layer is **rule-based, deterministic thresholds — no ML, no prediction.**

Live in production at <https://vernfy.com> (single VPS, Docker Compose behind Caddy).

Code and comments are written in **English**. Conversation with the user is in **Vietnamese**.

Authoritative docs — read these instead of re-deriving:

- `README.md` — product + architecture overview
- `docs/architecture.md` — service boundaries, Mermaid diagrams
- `docs/event-catalog.md` — every Kafka event: producer, consumers, payload
- `docs/intelligence.md` — risk rules, insights, anomalies: triggers, severities, metrics
- `docs/runbook.md` — startup, compose workflow, troubleshooting
- `docs/deploy.md` — production deployment
- `docs/security/jwt-key-rotation.md` — key rotation procedure
- `docs/unit-testing/unit-testing-1.txt` — full test catalog (513 backend tests, 111 classes,
  plus the frontend Vitest suite and the Playwright journeys)

---

# Technology Stack

Backend

- Java 21
- Spring Boot 4.0.6
- Spring Security
- Spring Data JPA
- Flyway (`ddl-auto: validate`)
- Spring for Apache Kafka
- JWT — RS256 asymmetric, JWKS discovery
- Lombok
- springdoc / OpenAPI

Infrastructure

- MySQL 8 — one instance, seven logical databases
- Redis — auth-service only (refresh tokens + brute-force lockout)
- Kafka — single-node KRaft broker
- Micrometer + Prometheus + Grafana + Alertmanager + Tempo

Frontend

- React 19 + TypeScript + Vite + TailwindCSS + Axios + React Router

Build & test

- Maven — **one standalone project per service, no aggregator pom**
- JUnit 5 + Mockito + AssertJ + Testcontainers + WireMock + Awaitility
- Docker Compose, GitHub Actions

---

# Development Commands

Each service under `services/` is a **standalone Maven project**. There is no root pom — running
`mvn` at the repo root does nothing. Always `cd` into the service first.

Each service **does** ship a Maven Wrapper (`./mvnw`, `mvnw.cmd` on Windows). Use it locally.
CI uses the runner's system `mvn` because `.mvn/wrapper/maven-wrapper.jar` is gitignored.

Start the stack first

```powershell
docker compose up --build -d
docker compose ps
docker compose down          # -v also drops mysql/prometheus/grafana volumes
```

Run one service

```powershell
cd services\transaction-service
.\mvnw spring-boot:run
```

Run all tests for a service

```powershell
.\mvnw verify
```

Run single test class

```powershell
.\mvnw test "-Dtest=RiskRuleEngineTest"
```

Run single method

```powershell
.\mvnw test "-Dtest=RiskRuleEngineTest#detectsHighAmountExpense"
```

Skip integration tests

```powershell
.\mvnw test "-Dtest=!*IntegrationTest"
```

**Docker must be running for any test command.** No pom configures Surefire or Failsafe, so
`*IntegrationTest` classes match Surefire's default `*Test` include and Testcontainers
(MySQL, Kafka, Redis) start during a plain `mvn test`. There is no unit-only profile.

Frontend

```powershell
npm install --prefix web
npm run dev   --prefix web    # :5173, proxies /api -> localhost:8080
npm run build --prefix web    # tsc -b && vite build
npm run lint  --prefix web
```

Production — never call `docker compose` directly on the prod box; secrets are SOPS-encrypted
and injected at launch:

```bash
scripts/prod-compose.sh up -d --build
scripts/prod-compose.sh ps
```

---

# Project Architecture

Nine services. Ports are fixed and referenced by compose, Prometheus, and the gateway.

| Service | Port | Database | Role |
|---|---|---|---|
| `api-gateway` | 8080 | – | Edge routing + JWT validation |
| `auth-service` | 8081 | `auth_db` | Register / login / refresh, lockout; holds the JWT private key |
| `user-service` | 8082 | `user_db` | User profiles |
| `transaction-service` | 8083 | `transaction_db` | Transactions, categories, wallets; produces `TransactionCreated` |
| `budget-service` | 8084 | `budget_db` | Budgets + utilization; consumes/produces |
| `dashboard-service` | 8085 | none (BFF) | Read-only aggregation |
| `risk-service` | 8086 | `risk_db` | Rules, insights, anomalies — **internal only** |
| `notification-service` | 8087 | `notification_db` | In-app notifications from `RiskDetected` |
| `analytics-service` | 8088 | `analytics_db` | Monthly rollup read model |

Layering inside a service

```
Controller
    ↓
Service Interface
    ↓
Service Implementation
    ↓
Repository
    ↓
Entity
```

Package root

```
com.pm.<service>          e.g. com.pm.transactionservice
com.pm.gateway            (the gateway is the one exception to the naming)
```

Typical packages: `config`, `controller` (or `web`), `dto`, `entity`, `repository`, `service`,
`service/impl`, `exception`, `security/jwt`. Plus domain packages: `rule`, `insight`, `anomaly`
in risk-service; `outbox`, `game` in transaction-service; `delivery`, `push`, `email`, `webhook`,
`narrator`, `stream` in notification-service.

DTOs are used for every API request/response. Never expose JPA entities directly.
DTOs are Lombok classes, not records — match the surrounding style.

## Invariants — enforced in code, do not break

- **No runtime cross-service calls except the dashboard BFF.** `dashboard-service` fans out to
  user/transaction/budget over REST relaying the caller's JWT, fail-fast. Every other
  cross-service coupling is asynchronous over Kafka.
- **`userId` comes only from the JWT** — never from a request body, path, or query param.
- **Every service validates the JWT locally**, so the gateway stays removable.
- **Flyway owns every schema.** Add a new migration; never edit an applied one.
- **DB-per-service.** Each service connects as its own least-privilege user (`<name>_user`),
  never as root.
- **`risk-service` is internal**: not behind the gateway, no JWT stack, port not published to the
  host. Its `/api/v1/{risks,insights,anomalies}` controllers are unauthenticated by design.
- **Every service carries `logging/CorrelationIdFilter`** (registered at `HIGHEST_PRECEDENCE` by
  `config/CorrelationIdFilterConfig`) and sets `LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs` in compose.
  A new service needs both, or its lines drop out of a cross-service log trace. The id also
  travels **on Kafka**: producers put it on an `X-Correlation-ID` record header (the outbox
  persists it per row, since the relay publishes off the request thread) and every consumer
  service registers a `logging/CorrelationIdRecordInterceptor` that lifts it back into the MDC.
  A new consumer needs that interceptor too.

## Event backbone

Single-node KRaft Kafka. JSON without type headers, keyed by `userId`, at-least-once delivery.

| Topic | Producer | Consumers |
|---|---|---|
| `finsight.transactions.created` | transaction-service | budget, risk, analytics |
| `finsight.budgets.changed` | budget-service | risk |
| `finsight.budgets.exceeded` | budget-service | notification |
| `finsight.risk.detected` | risk-service | notification |

- Producers use a **transactional outbox** — `OutboxWriter` writes inside the business
  transaction, `OutboxRelay` publishes (`transaction-service/.../outbox/`).
- Consumers are **idempotent via a `processed_events` inbox table** (budget, notification,
  analytics). Any new consumer must follow this pattern.
- Changing a risk rule threshold means updating `docs/intelligence.md` in the same change.

---

# Security Architecture

Authentication

- Stateless JWT, **RS256 asymmetric**

`auth-service` is the **only** holder of the private key. It publishes a JWK Set at
`/.well-known/jwks.json`; every other service carries a `security/jwt/JwtKeyResolver` that
discovers the verification key by `kid` (RFC 7638 thumbprint). Rotation uses an overlap window —
follow `docs/security/jwt-key-rotation.md`.

`scripts/rotate-jwt-key.sh` targets a plaintext `.env` and is **not** SOPS-aware — do not run it
against production without adapting it.

Authorization

- `ROLE_USER`
- `ROLE_ADMIN`
- `ROLE_ANALYST`
- `ROLE_GAMER` — gates the LuckyMe mini-games section (also allowed for `ROLE_ADMIN`)

Seeded by Flyway in `auth-service` (`V2__seed_roles.sql`, `V6__seed_gamer_role.sql`).
There is no auto-seeded admin: promote the first admin by setting `users.role_id` in `auth_db`,
then re-login.

Controllers resolve the caller from the JWT and pass a resolved value into services.
Never pass `Authentication` into business logic.

Whenever adding an endpoint, update that service's `SecurityConfig` accordingly.

Production network posture

- Only Caddy publishes ports. MySQL, Redis, Kafka, and every service port are unpublished.
- To reach a service on prod, use a curl sidecar sharing its network namespace:

```bash
docker run --rm --network container:finsight-auth-service curlimages/curl:latest \
  -s http://localhost:8081/actuator/health
```

- Cloudflare Bot Fight Mode returns **403 to curl's default User-Agent**. Pass
  `-A 'Mozilla/5.0 ...'` before concluding the site is down.

---

# Configuration & Secrets

No secret is inlined in compose. Everything is interpolated from a gitignored `.env` with
`${VAR:?}` guards, so compose refuses to start with a clear message when one is missing.

Local setup

```powershell
copy .env.example .env
```

Then run `scripts/gen-jwt-keys.sh` (Git Bash) and paste the keypair into `JWT_PRIVATE_KEY` /
`JWT_PUBLIC_KEY`, plus `MYSQL_ROOT_PASSWORD` and the per-service `*_DB_PASSWORD` values.

MySQL init scripts in `docker/mysql/init/` run **only against an empty data dir**. After adding a
database or user, recreate the volume with `docker compose down -v`.

Monitoring services sit behind the `monitoring` compose profile.

Production secrets live in `secrets.env`, SOPS-encrypted with age:

```bash
sops secrets.env
sops -d secrets.env
sops set secrets.env '["KEY"]' '"value"'
```

Never commit, and never print to chat:

- `.env`, `secrets.env`
- `*.agekey`, `keys.txt` — the age **private** key exists only at
  `/root/.config/sops/age/keys.txt` on prod plus an off-box backup. Lose it and `secrets.env`
  is unrecoverable.
- `docker/caddy/certs/` — Cloudflare Origin certificate + key
- `finsight.pub` at the repo root — misnamed, it is actually a **private** SSH key, gitignored
  and treated as compromised

`.sops.yaml` records only the age *recipient* and is committed.
`project-status.md` and `state.md` are gitignored personal scratch notes — do not link them from
committed docs.

---

# Error Handling

Every user-facing service has a `exception/GlobalExceptionHandler` (`@RestControllerAdvice`).
`api-gateway` and `risk-service` do not — the gateway is reactive, risk-service is internal.

Throw the service's own domain exceptions and let the handler map them.
Do not add ad-hoc try/catch in controllers to produce error responses.

---

# CI

- `.github/workflows/ci.yml` — matrix over all nine services, `mvn -B -ntp verify`,
  `fail-fast: false`, Surefire reports uploaded on failure.
  **Adding a service means adding it to this matrix** — there is no aggregator pom to pick it up.
- `.github/workflows/security.yml` — gitleaks over full history (**blocking**) + Trivy filesystem
  scan (report-only via `continue-on-error`, vuln DBs pulled from the AWS ECR mirror to avoid
  ghcr rate limits).
- `.github/workflows/codeql-java.yml` + `codeql-web.yml` — SAST, split by language and
  path-filtered (`services/**` vs `web/**`) so a change to one side does not run the other's
  analysis; the weekly cron in both is deliberately *not* path-filtered. Both are
  `build-mode: none` (no aggregator pom, so nothing is compiled for extraction) with the default
  query suite; `.github/codeql/codeql-config.yml` drops test sources. **That config file only
  applies while the mode stays `none`.** Alerts go to Security → Code scanning; the workflows
  never fail on a finding. Keep the `category:` values stable — changing one re-files every
  existing alert as new.
- `.github/workflows/frontend.yml` — `web/` only (path-filtered): `npm ci`, ESLint, Vitest, then
  `tsc -b && vite build`.
- `.github/dependabot.yml` — nine Maven directories + npm `/web` + github-actions, weekly.
  Major `typescript` bumps are ignored for `/web`: typescript-eslint's peer range excludes each
  new major, so the PR dies at `npm ci` (ERESOLVE) and reddens every job that installs. Lift the
  entry once typescript-eslint widens the range, and bump both together.

---

# Coding Standards

Always

- Constructor Injection
- DTO for API communication
- Business logic inside Services
- Repository only for persistence
- Follow existing naming conventions
- Match existing formatting

Avoid

- Field Injection
- Business logic inside Controllers
- Returning Entities
- Static mutable state

---

# When Modifying Existing Code

Before editing

- understand the existing implementation
- preserve current architecture
- prefer extending existing code over rewriting

Never

- rename packages
- reorganize folders
- upgrade dependencies
- introduce new libraries
- change project structure

unless explicitly requested.

**Load-bearing names — never rename, they are wired into compose, Prometheus scrape config, and
Grafana dashboards:**

- packages `com.pm.*` / `com.finsight.*`
- Kafka topics and metric names beginning with `finsight.`
- compose service and container names
- database names

---

# Git Workflow

**The user pushes. Claude only prepares commits.**

Never run `git push` — not to any branch, not even when the commit was requested.
Never commit unless explicitly asked; finishing a task means leaving the changes in the working
tree and reporting them.

When a commit *is* requested:

- Branch first (`feat/`, `fix/`, `chore/`, `docs/` as in existing history).
  Never commit directly onto `main`.
- **One-line commit message**, imperative, Conventional-Commits prefix — matching history:
  `docs: add full test-suite catalog and link it from the README`
- **No `Co-Authored-By` trailer.** No "Generated with Claude Code" footer. No multi-paragraph
  bodies unless asked.
- Stage only the files belonging to the task — never `git add -A` over an unrelated dirty tree.

Then report the branch and commit hash and stop; the user reviews and pushes.

---

# Environment Notes

- Primary working directory: `D:\finsight`. Shell is PowerShell.
- On Windows, `bash` resolves to a broken WSL install. Use the **Git Bash** tool for `.sh`
  scripts and PowerShell for everything else.
- Production: Hetzner CX33, reachable as `ssh vernfy`, repo at `/root/finsight-platform`,
  Cloudflare DNS with the **proxy ON** (`vernfy.com` and `www.` resolve to Cloudflare anycast
  IPs, not the box). Cloudflare terminates browser TLS with Universal SSL (`vernfy.com` +
  `*.vernfy.com`); Caddy serves a **Cloudflare Origin certificate** that only Cloudflare trusts,
  so the proxy must stay ON and SSL/TLS mode on **Full (strict)** — turning the proxy off would
  hand browsers an untrusted cert. `www.` 308-redirects to the apex (see `docker/caddy/Caddyfile`).

---

# Testing Expectations

Bug fixes

1. Reproduce
2. Fix
3. Verify

New features

- add or update tests whenever appropriate

Refactoring

- behavior must remain identical

---

# Definition of Done — Keep the Docs in Sync

After finishing a piece of work, update these in the **same change**, before reporting done.
Do not wait to be asked.

- `README.md` — whenever the change alters what the platform does, how it is built, run,
  tested, or deployed, or what is still missing. Adding a capability means also deleting its
  bullet from the "Roadmap / not yet built" section.
- `project-status.md` — the personal progress tracker. Update the `Cập nhật:` date + `origin/main`
  ref line, the status tables, the milestone percentages, and tick off the numbered item the work
  closes. It is **gitignored** — update it, never stage it, never link it from committed docs.
- `docs/unit-testing/unit-testing-1.txt` — whenever tests are added, removed, or renamed: the test
  class, its test names, and the running totals at the top of the file.
- The other authoritative docs when the change touches their subject — `docs/architecture.md`,
  `docs/event-catalog.md`, `docs/intelligence.md` (a rule threshold change **must** update this),
  `docs/runbook.md`, `docs/deploy.md`.

Only update what the change actually affects. This is a sync obligation, not a licence to rewrite
unrelated documentation.

# Success Checklist

Before finishing, verify:

- Requirements satisfied
- No unrelated code changed
- Architecture respected
- Security rules respected
- DTO usage preserved
- Tests pass (if available)
- Build succeeds (when applicable)
- No unused imports
- No dead code introduced

Only then consider the task complete.
