# FinSight — Project Status

_Last updated: 2026-06-15_
_Repo: `D:\finsight` · Branch: `feature/ci-github-actions` · Working tree: clean_

> **Looking for the two scorecards?** Jump to **[§9 CV / Portfolio readiness](#9-cv--portfolio-readiness-detailed-scorecard)**
> and **[§10 Deploy-to-internet readiness](#10-deploy-to-internet-readiness-detailed-scorecard)**.
> They answer, item by item: *what is done, what is missing, how far off, and what to do next*
> for the two goals — **putting this on a CV** and **getting it live on the internet**.

FinSight's **original vision** is a *Financial Intelligence & Risk Monitoring Platform* —
explicitly **not** just an expense tracker. This document measures progress against that
chartered scope, not against the current repository alone.

**Stack (today):** Java 21 · Spring Boot 4.0.6 · Spring Security · Spring Data JPA · Flyway ·
MySQL 8 · Redis · Kafka (KRaft) · JWT (HS512) · springdoc/OpenAPI · Micrometer ·
Prometheus · Grafana · Docker Compose · GitHub Actions · Testcontainers.

> Companion docs (added in this pass): [docs/architecture.md](docs/architecture.md),
> [docs/event-catalog.md](docs/event-catalog.md), [docs/intelligence.md](docs/intelligence.md),
> [docs/runbook.md](docs/runbook.md).

---

## 0. Progress at a glance — three axes (not one number)

A single "% complete" mixes very different goals. Progress is tracked on three independent axes:

| Axis | What it measures | Progress |
|---|---|---|
| **MVP backend** | Core finance CRUD + auth + dashboard working end-to-end | **~90%** |
| **Production-ready MVP** | The MVP, operable & secure for real deployment | **~72%** |
| **Full FinSight vision** | The chartered Intelligence & Risk platform | **~65%** |

**Headline:** The MVP (finance tracker + budgets + dashboard) is built, and the platform now
has a **real multi-topic event backbone** (3 topics, 2 producers, 2 idempotent consumers) plus
**full Prometheus/Grafana observability** and **OpenAPI docs**. The product's defining half —
*Intelligence & Risk* — is **no longer at zero**: risk-service implements rule-based
**Risk Monitoring**, **Behavioral Insights**, and **Anomaly Detection** end-to-end (Phases D–F).
The remaining vision gaps are **gRPC** (0%), a **Notification Service** (0% — `RiskDetected` has
no consumer), and a deeper **Analytics** engine.

---

## 1. Phase completion (implemented & verified in code)

All phases below are **DONE** — implemented, with unit + Testcontainers integration tests.

| Phase | Scope | Evidence in code |
|---|---|---|
| **A** | OpenAPI/Swagger rollout (springdoc 3.0.3) to auth, user, budget, dashboard (transaction piloted) | `OpenApiConfig` + SecurityConfig permit-list + `/v3/api-docs` test per service |
| **2.1** | transaction-service **produces** `TransactionCreated` (AFTER_COMMIT, keyed by userId) | `event/` package; topic `finsight.transactions.created` |
| **2.2** | budget-service **consumes** `TransactionCreated` → materializes `spent_amount`; idempotency inbox | `TransactionEventConsumer`, `processed_events`; ADR-0004 |
| **C.1** | Micrometer → Prometheus → Grafana; datasource + **Platform Overview** dashboard | compose `prometheus`/`grafana`; `docker/prometheus`, `docker/grafana` |
| **C.2** | `finsight.budget.events.failed` (retry-exhaustion) + **Event Pipeline** dashboard | `KafkaConsumerConfig` recoverer; `finsight-event-pipeline.json` |
| **D.1** | risk-service foundation: consume `TransactionCreated`, `HIGH_AMOUNT_EXPENSE`, **produce** `RiskDetected` | `RiskEventConsumer`, `RiskDetectedEvent`; topic `finsight.risk.detected` |
| **D.2** | Persist detections to `risk_alerts`; read API `GET /api/v1/risks` | `RiskAlert`, `RiskAlertService`, `RiskAlertController` |
| **D.3** | Detection metrics `finsight.risk.events.detected{type,severity}` + **Risk** dashboard | `RiskEventConsumer`; `finsight-risk.json` |
| **D.4** | `observed_expenses` read-model + windowed rules `RAPID_SPENDING`, `LARGE_DAILY_SPEND` | `RiskRuleEngine`, `ObservedExpenseRepository` |
| **E.1** | Behavioral insight `SPENDING_INCREASE`; `insights` table; `GET /api/v1/insights`; `finsight.insights.generated` | `InsightService`, `Insight`, `InsightController` |
| **E.2** | `CATEGORY_SURGE` + `BUDGET_RISK`; budget read-model from **`BudgetChanged`** (budget-service produces it) | `BudgetEventConsumer`, `budget_snapshots`; `KafkaBudgetEventPublisher` |
| **E.3** | `LOW_SAVINGS_RATE`; INCOME recorded into `observed_expenses` (income side) | `InsightService`, V7 migration |
| **F.1** | Anomaly `UNUSUAL_TRANSACTION_AMOUNT`; `anomalies` table; `GET /api/v1/anomalies`; `finsight.anomalies.detected` | `AnomalyService`, `Anomaly`, `AnomalyController`, V8 migration |

Detailed trigger conditions, severities, persistence, and metrics for D–F are in
[docs/intelligence.md](docs/intelligence.md).

---

## 2. Chartered scope vs. reality

### Core domains (5)

| Core domain | Status | Notes |
|---|---|---|
| Personal Finance Management | ✅ ~85% | Transactions, categories, budgets |
| Dashboard & Analytics | ⚠️ partial | Dashboard ✅; a dedicated *Analytics* engine is still ~30% |
| Behavioral Insights | ✅ MVP | Rule-based: SPENDING_INCREASE, CATEGORY_SURGE, BUDGET_RISK, LOW_SAVINGS_RATE (E.1–E.3) |
| Anomaly Detection | ✅ MVP | Rule-based: UNUSUAL_TRANSACTION_AMOUNT (F.1) |
| Financial Risk Monitoring | ✅ MVP | Rule-based: HIGH_AMOUNT_EXPENSE, RAPID_SPENDING, LARGE_DAILY_SPEND (D.1–D.4) |

The three intelligence domains now have **working rule-based implementations** (no ML — and ML
is explicitly out of scope for v1). The remaining depth (richer analytics, more rules) is
incremental, not greenfield.

### Services (8 chartered)

| Service (charter) | Reality | Mapping / completeness |
|---|---|---|
| API Gateway | ✅ exists | Proxy + edge JWT validation; **no rate limiting** → ~80% |
| Auth Service | ✅ exists | JWT, refresh, lockout, Redis-backed → ~90% |
| User Service | ✅ exists | Profile data → ~85% |
| Transaction Service | ✅ exists | INCOME/EXPENSE + categories/summaries; **no TRANSFER** → ~75% |
| Budget Service | ✅ exists | Definitions + event-driven utilization → ~85% |
| **Analytics Service** | ⚠️ substituted | `dashboard-service` (BFF) covers presentation, not analysis → ~30% |
| **Risk Intelligence Service** | ✅ exists | `risk-service`: Risk + Insights + Anomaly (rule-based MVP) → ~70% |
| **Notification Service** | ❌ absent | 0% — `RiskDetected` is published but has **no consumer** yet (see §5.1) |

`dashboard-service` is an **extra** BFF, not one of the 8 chartered services.

### Communication pillars (3)

| Pillar | Charter | Reality |
|---|---|---|
| REST (external) | required | ✅ implemented |
| gRPC (internal sync) | required | ❌ **0%** — no proto, no deps; internal calls are REST (`RestClient`) |
| Kafka (async events) | required | ✅ **real backbone** — 3 topics, 2 producers (`TransactionCreated`, `BudgetChanged`), 2 idempotent consumers (budget, risk), 1 best-effort output (`RiskDetected`). See [docs/event-catalog.md](docs/event-catalog.md) |

### Infrastructure

| Item | Charter | Reality |
|---|---|---|
| MySQL (DB-per-service) | ✅ | ✅ five logical DBs (auth/user/transaction/budget/risk) on one instance |
| Redis | ✅ | ⚠️ only auth-service uses it (refresh tokens + lockout); no other service depends on Redis (the gateway has **no** Redis dependency) |
| Kafka | ✅ | ✅ single-node KRaft broker; full producer→consumer flows (Phases 2.1–F.1) |
| Docker | ✅ | ✅ |
| CI/CD | ✅ | ✅ GitHub Actions (build + test) |
| OpenAPI/Swagger | ✅ | ✅ Phase A on the 5 user-facing REST services (auth/user/transaction/budget/dashboard); gateway + internal risk-service excluded |
| Monitoring (Prometheus/Grafana) | ✅ | ✅ Phase C — scrape of all 7 services + 3 provisioned dashboards |

### Explicitly out-of-scope for v1 (correctly absent)

Saga · CQRS · Event Sourcing · ELK · Keycloak · Istio · Helm · ArgoCD · ML · Blockchain ·
**separate Audit Service**. None of these count against progress.

---

## 3. Architecture as built (what exists today)

| Service | Port | Owns DB | Responsibility |
|---|---|---|---|
| api-gateway | 8080 | – | Edge routing + JWT validation (HS512/iss/aud), error envelope |
| auth-service | 8081 | `auth_db` | Register, login, refresh, account lockout; Redis-backed tokens |
| user-service | 8082 | `user_db` | User profile data |
| transaction-service | 8083 | `transaction_db` | Transactions (INCOME/EXPENSE), categories, summaries; produces `TransactionCreated` |
| budget-service | 8084 | `budget_db` | Budget definitions + utilization; consumes `TransactionCreated`, produces `BudgetChanged` |
| dashboard-service | 8085 | none (BFF) | Aggregates user + transaction + budget; fail-fast; relays JWT |
| risk-service | 8086 | `risk_db` | Risk rules, insights, anomaly; consumes `TransactionCreated` + `BudgetChanged`, produces `RiskDetected`; read APIs |
| mysql / redis / kafka | – | – | Shared MySQL (DB-per-service) + Redis (auth) + single-node KRaft broker |
| prometheus / grafana | 9090 / 3000 | – | Metrics scrape + dashboards |

**Design rules:** no runtime cross-service calls between business services (only the dashboard
BFF calls others); all other coupling is Kafka; `userId` read only from the JWT; Flyway owns the
schema (`ddl-auto: validate`); shared HMAC secret; gateway is removable (services validate
tokens independently); risk-service is internal (no JWT stack, not behind the gateway).

Full diagrams: [docs/architecture.md](docs/architecture.md).

---

## 4. Capabilities accomplished

- End-to-end auth: register → login → JWT (HS512) → authorized calls through the gateway, with
  per-account lockout and Redis-backed token/session data.
- Full CRUD + reporting in transaction and budget services (summaries, trends, utilization).
- A stateless BFF aggregating three services with fail-fast behavior and JWT relay.
- **A real Kafka backbone:** `TransactionCreated` and `BudgetChanged` produced AFTER_COMMIT;
  budget-service materializes utilization idempotently; risk-service derives risk/insight/anomaly.
- **Rule-based intelligence MVP** in risk-service across all three domains (Phases D–F), each
  persisted and exposed over a read API, each emitting Micrometer metrics.
- Observability: every service scraped by Prometheus; 3 provisioned Grafana dashboards
  (Platform Overview, Event Pipeline, Risk); ECS JSON logging with correlation IDs.
- OpenAPI/Swagger on every REST service.
- Flyway-owned schemas, `validate`-only Hibernate, DB-per-service isolation.
- Containerized stack with readiness-gated startup; CI with Testcontainers integration tests.

---

## 5. Remaining roadmap (separate from completed work)

**Vision-defining (the chartered "second half"):**
1. **Notification Service** — consume `RiskDetected` and deliver alerts. The topic and producer
   already exist; only the consumer/delivery side is missing. (Scope note in §5.1.)
2. **gRPC (internal sync)** — architectural pillar at 0%; no proto, no deps.
3. **Analytics engine** — distinct from the dashboard's presentation; deeper rollups/analysis.
4. **More intelligence rules** — incremental additions on the existing risk-service framework
   (explicitly **not** part of this documentation pass; e.g. Phase F.2 is **not** started).

**Product features:**
5. **Transaction `TRANSFER`** type + wallet-to-wallet semantics (`walletId` is scaffolded).
6. **In-service audit logging** — only JPA timestamp auditing exists today (see §5.1).

**Production-readiness (axis 2):**
7. Merge the feature branch and get a **green CI run** (JDK 21).
8. Replace the **shared symmetric HMAC secret** with asymmetric signing (RS256/ES256) — today
   every service can *mint* tokens, not just verify. Biggest structural risk.
9. Real **deployment target** (K8s/ECS), TLS/HTTPS, managed secrets store.
10. Edge **rate limiting**; gateway max-request-body limit; retries/circuit breakers.
11. **Transactional outbox** to close the AFTER_COMMIT dual-write gap (ADR-0004).
12. Distributed tracing; Prometheus **alerting** rules; backup/restore for the shared MySQL.
13. Load/performance tests; whole-stack E2E in CI; SAST/dependency/secret scanning.

### 5.1 Scope clarifications (unchanged)

- **Audit:** the charter keeps audit as *in-service logging*, with a separate Audit Service only
  "if time allows." A missing Audit *Service* is not a gap; but the real requirement — action/
  security audit logging inside each service — is **not implemented** (only JPA timestamp
  auditing). Item is *open*.
- **Notification — charter conflict:** listed in the chartered architecture (1 of 8 services) and
  not in the v1 out-of-scope list, so by the written charter it is **required**. `RiskDetected`
  is already produced; building the consumer/delivery side resolves this.

---

## 6. Production-readiness gaps (axis 2)

**Blocking (before any real deployment)**
- Green CI run on a merged branch.
- Asymmetric JWT signing (RS256/ES256) — replace the shared HMAC secret.
- No deployment target (K8s/ECS), no TLS/HTTPS, no managed secrets store (local `.env`).
- Single shared MySQL = shared failure domain; no backup/restore strategy.

**Observability — now substantially addressed**
- ✅ Micrometer/Prometheus across all 7 services; ✅ 3 Grafana dashboards; ✅ ECS JSON logging +
  correlation IDs (on api-gateway/transaction/dashboard).
- ⚠️ Correlation-ID/ECS rollout to auth/user/budget pending; no distributed tracing; **no alerting**.

**Resilience & API**
- No edge rate limiting; auth endpoints unthrottled (the gateway has no Redis dependency to back a limiter yet).
- No gateway max-request-body limit; no retries/circuit breakers (dashboard is fail-fast).
- No token revocation/denylist for access tokens.
- Kafka delivery is at-least-once with an accepted AFTER_COMMIT dual-write gap (no outbox).

**Testing & process**
- No load/performance tests, no whole-stack E2E in CI, no security scanning.
- No staging environment; runbook now exists ([docs/runbook.md](docs/runbook.md)) but no
  rollback automation.

---

## 7. CV positioning (honest)

A **6→7-service Spring Boot 4 / Java 21 platform** with real microservice decomposition, an
explicit BFF, a working **Kafka event backbone** (idempotent consumers, read-models), a
**rule-based intelligence layer** (risk/insights/anomaly), and an **observability stack**
(Prometheus/Grafana, structured logging) — described as a *finance-intelligence MVP with strong
engineering hygiene*, not a production-deployed, ML-driven platform.

Genuine strengths: enforced service boundaries; security depth (JWT algorithm pinning +
issuer/audience, lockout, secret externalization, least-privilege DB users, rotation runbook);
event-driven design with idempotency and documented tradeoffs (ADR-0004); and operational
maturity (Actuator probes, Docker healthchecks, CI with Testcontainers, dashboards).

**Be honest in interview:** learning/portfolio project, not production-deployed; intelligence is
**rule-based, not ML**; gRPC and a Notification Service are not built; load/scale and a real
deployment target are absent.

---

## 8. Recommended next order

1. Green CI run on a merged branch.
2. Build the **Notification Service** (consume `RiskDetected`) — closes the last chartered
   service and gives `RiskDetected` a consumer.
3. Analytics engine (distinct from the dashboard BFF).
4. gRPC for internal sync calls.
5. Transaction `TRANSFER` type; in-service audit logging.
6. RS256/JWKS migration; edge rate limiting; transactional outbox.
7. Distributed tracing + Prometheus alerting.

---

## 9. CV / Portfolio readiness (detailed scorecard)

**Goal of this section:** is the project good enough to put on a CV and defend in an interview —
and if not 100%, exactly what closes the gap. This is *separate* from "is it deployed" (§10): a
project can be fully CV-worthy without ever being publicly hosted.

**Overall CV readiness: ~85% — already a strong portfolio centerpiece.** You can put it on a CV
**today**. The remaining 15% is polish that makes it *interview-proof* and visually convincing
(a live demo or screenshots, a green CI badge, a 60-second walkthrough).

| # | What a reviewer looks for | Status | Evidence / Gap |
|---|---|---|---|
| 1 | **Real architecture, not a toy** | ✅ ~95% | 7 Spring Boot 4 / Java 21 services, DB-per-service, BFF, Kafka backbone (3 topics, 2 producers, 2 idempotent consumers), enforced service boundaries. This is the headline strength. |
| 2 | **Non-trivial domain logic** | ✅ ~85% | Rule-based risk / insights / anomaly layer (Phases D–F), event-sourced read-models, idempotency inbox. Demonstrates more than CRUD. |
| 3 | **Testing discipline** | ✅ ~85% | Unit + Testcontainers integration tests per service (real MySQL + Kafka); consumer-lag metric assertions. Shows you test the hard parts. |
| 4 | **CI** | ✅ ~80% | GitHub Actions matrix builds+tests all 7 services on every PR/push (JDK 21). Gap: branch not merged to `main` with a visible **green run + README badge** (see §10 #2). |
| 5 | **Documentation** | ✅ ~95% | README, architecture.md, event-catalog.md, intelligence.md, runbook.md, ADR-0004, this status doc. Far above typical portfolio level. |
| 6 | **Security awareness** | ✅ ~80% | JWT algorithm pinning + iss/aud, account lockout, least-privilege DB users, secret externalization, rotation runbook. Honest about the shared-HMAC weakness — *good* interview material. |
| 7 | **Observability** | ✅ ~85% | Prometheus scrape of all 7 services, 4 Grafana dashboards (incl. consumer lag), ECS JSON logging + correlation IDs. |
| 8 | **Demonstrability** | ⚠️ ~70% | 4 Grafana dashboard screenshots now committed to `docs/images/` and embedded in the README. Remaining gap: no live demo URL and no short demo video/GIF. |
| 9 | **Repo hygiene & narrative** | ✅ ~80% | Clean commit history, conventional commits, ADRs. Gap: several stale top-level `*_REVIEW_REPORT.md` files clutter the root and a stale "six/four services" comment lingers — minor cleanup. |
| 10 | **Honest framing** | ✅ 100% | Status docs already state plainly: portfolio project, rule-based (not ML), not production-deployed, gRPC/Notification absent. This honesty is an asset in interviews. |

### To push CV readiness from ~85% → ~95% (highest leverage first)
1. **Add visual proof** (partly done — closes most of #8): ✅ 4 Grafana screenshots committed to
   `docs/images/` and embedded in the README. ⬜ Still record a ~60s GIF/video of the end-to-end
   flow (register → login → create transaction → see budget utilization update → see a risk alert).
2. **Merge to `main` + add a CI status badge** to the README (closes #4) — a green badge is the
   single most credible "it really builds and tests" signal on a CV repo.
3. *(Optional, high impact)* a **live demo URL** — but that is the §10 deploy track; a hosted
   demo turns "portfolio project" into "I shipped it."
4. **Tidy the repo root** (closes part of #9): move/delete the stale `*_REVIEW_REPORT.md`,
   `VALIDATION_REPORT.md`, etc. into an `archive/` folder or remove — keep the root to README +
   project-status + docs/.

### One-line CV bullet (ready to use)
> *Built **FinSight**, an event-driven financial-intelligence platform — 7 Java 21 / Spring Boot 4
> microservices, DB-per-service, a Kafka event backbone with idempotent consumers and read-models,
> a rule-based risk/insight/anomaly engine, full Prometheus/Grafana observability, and a
> Testcontainers-backed GitHub Actions CI pipeline.*

---

## 10. Deploy-to-internet readiness (detailed scorecard)

**Goal of this section:** what stands between the current repo and a URL a stranger can open. This
is the harder, more expensive track than §9.

**Overall deploy readiness: ~35% — buildable and containerized, but not yet internet-safe or
hosted.** Every service has a Dockerfile and the whole stack runs under Docker Compose locally;
what is missing is a *host*, *TLS*, *real secret management*, and hardening the dev-only security
posture before anything faces the public internet.

### What already exists (the foundation)

| Capability | Status | Evidence |
|---|---|---|
| Per-service container images | ✅ | 7 `services/*/Dockerfile` (multi-stage `mvn` builds) |
| Full local orchestration | ✅ | root `docker-compose.yml` — 12 services incl. MySQL, Redis, Kafka, Prometheus, Grafana |
| Readiness-gated startup | ✅ | healthchecks + `depends_on: condition: service_healthy` |
| Secrets externalized (not in compose) | ✅ | gitignored `.env`, interpolated; compose refuses to start if unset |
| Least-privilege DB users | ✅ | one single-DB user per service at MySQL init |
| Build+test automation | ✅ | GitHub Actions matrix (does **not** yet publish images) |
| Internal-only risk-service | ✅ | port 8086 not host-published (SE-2) |

### Blocking gaps (must close before public exposure)

| # | Gap | Status | Effort | Why it blocks |
|---|---|---|---|---|
| 1 | **No host / deploy target** | ❌ 0% | M | Nothing runs anywhere but localhost. Need a VPS, or a managed container platform. |
| 2 | **Branch not merged + no published images** | ⚠️ | S | CI builds & tests but doesn't push images to a registry (GHCR/Docker Hub); deploy needs pullable artifacts. Also merge `feature/ci-github-actions` → `main` for a clean release line. |
| 3 | **No TLS / HTTPS** | ❌ 0% | S–M | Browsers and JWT-over-the-wire need HTTPS. Need a reverse proxy with auto-certs (Caddy/Traefik + Let's Encrypt) or a platform that terminates TLS. |
| 4 | **No managed secrets** | ⚠️ | S | `.env` on a server is acceptable for a hobby demo but not real; a public deploy wants a secrets store or at least locked-down env injection. |
| 5 | **Dev-only security posture** | ⚠️ | M | Grafana allows **anonymous admin**; `/actuator/prometheus` is unauthenticated; **no edge rate limiting**; shared symmetric **HMAC** secret (every service can mint tokens). All fine on localhost, unsafe on the open internet. |
| 6 | **Single shared MySQL, no backup** | ⚠️ | M | One instance = shared failure domain; no backup/restore. For a demo, snapshot the volume; for real, a managed DB. |
| 7 | **No domain / DNS** | ❌ 0% | S | Need a domain (or a platform-provided subdomain) to point at the host. |
| 8 | **Resource sizing** | ⚠️ | S | 7 JVMs + Kafka + MySQL + Redis + Prometheus + Grafana is heavy — realistically needs a host with **≥ 4 GB (ideally 8 GB) RAM**. Don't try a 1 GB free tier. |

Legend: effort S = hours, M = a day or two, L = more.

### Two realistic deploy paths

**Path A — "Demo on the internet" (fastest, ~1–2 days, recommended for a CV link)**
A single VPS (e.g. 8 GB droplet/VM) running the existing `docker-compose.yml`, fronted by
**Caddy** or **Traefik** for automatic HTTPS:
1. Merge to `main`; have CI **build & push images to GHCR** (add a publish job).
2. Provision one VPS; install Docker + Compose; open only 80/443 in the firewall.
3. Add a reverse proxy (Caddy) terminating TLS for a domain → `api-gateway:8080`.
4. Put the real `.env` on the box (chmod 600); keep MySQL/Kafka/Redis/risk-service **off** the
   public network (only the gateway and, optionally, a password-protected Grafana exposed).
5. Harden: turn off Grafana anon-admin, set a strong admin password; add basic rate limiting at
   the proxy; restrict `/actuator/**` to localhost.
6. Schedule a nightly `mysqldump`/volume snapshot.
→ Closes #1, #3, #4(partial), #5(partial), #6(partial), #7. Good enough for a portfolio demo URL.

**Path B — "Production-grade" (weeks, only if the goal is true production)**
Managed Kubernetes/ECS, managed MySQL + managed Kafka (MSK/Confluent), RS256/JWKS for JWT,
HashiCorp Vault / cloud secrets manager, ingress + WAF + rate limiting, Prometheus alerting +
distributed tracing, blue/green or rolling deploys, IaC (Terraform). This is the §5–§6 roadmap;
**out of scope for a portfolio** unless deployment itself is the thing you want to showcase.

### Recommendation
For the stated goals (CV + a live link), do **§9 #1–#2** then **Path A**. That yields a
screenshot-rich, badge-bearing repo *and* a working `https://…` demo — the maximum CV return for
the least operational cost — without taking on the full production-hardening backlog (§6), which
can stay explicitly future-scoped.

---

## 11. Refined execution roadmap (ROI-ordered)

This is the agreed plan for the two goals (CV + live demo), sequenced cheapest-high-impact first.
**Recommended stop line for a portfolio is the end of Phase 3** — that already delivers a
screenshot-rich, CI-badged repo *and* a public `https://…` demo. Phase 4 is optional depth.

### Phase 1 — Foundation ✅ DONE
Architecture · Domain logic · Testing · Observability. (See §1–§4.)

### Phase 2 — Make it presentable (NOW · highest ROI · ~1 day)
Order matters: **merge first so the badge is meaningful.**
1. ⬜ **Merge `feature/ci-github-actions` → `main`** — establish a clean release line.
2. ⬜ **CI status badge** in the README (points at `main`; only credible once #1 is done).
3. ✅ **Screenshot package** → 4 Grafana screenshots committed to `docs/images/` and embedded in
   the README (Platform Overview, Event Pipeline, Risk, Consumer Lag).
4. ⬜ **Demo video / GIF** (~60s): register → login → create transaction → budget utilization
   updates → risk alert appears. (#3 and #4 can run in parallel with #1–#2.)

### Phase 3 — Put it on the internet (Path A · ~1–2 days)
**Includes the security hardening that the original plan was missing — non-negotiable before any
public exposure.**
1. ⬜ **Publish images** — add a CI job that builds & pushes to GHCR (so the VPS can pull).
2. ⬜ **VPS** — provision a host with **≥ 4 GB (ideally 8 GB) RAM**; install Docker + Compose;
   firewall open only on 80/443.
3. ⬜ **Domain** — point a domain/subdomain at the host.
4. ⬜ **HTTPS** — reverse proxy (Caddy/Traefik) with Let's Encrypt auto-certs → `api-gateway:8080`.
5. ⬜ **Security hardening (BLOCKER before public exposure):**
   - Disable Grafana anonymous admin; set a strong admin password.
   - Expose **only** the gateway (and optionally a password-protected Grafana); keep
     MySQL / Kafka / Redis / risk-service on the internal network only.
   - Restrict `/actuator/**` to localhost / internal.
   - Basic rate limiting at the reverse proxy.
   - Real `.env` on the box, `chmod 600`.
6. ⬜ **Backup** — nightly `mysqldump` or volume snapshot.

> ⛳ **Stop line for the portfolio goal.** After Phase 3 you have maximum CV return for minimum
> operational cost. Everything below is optional depth, not required for CV or demo.

### Phase 4 — Optional ops depth (only to showcase more, or to pursue production)
Priority order (diminishing returns for a no-real-load demo):
1. ⬜ **Distributed tracing** — best single talking point; visualizes the multi-service flow.
2. ⬜ **Alertmanager** — natural next step on the existing Prometheus + consumer-lag dashboard.
3. ⬜ **Resilience4j** (circuit breakers/retries) — last; value is hard to demo without real
   load/failures.

> **Deliberately *not* in this roadmap** (kept as documented future work / interview talking
> points, see §5–§6): RS256/JWKS migration and the transactional outbox. Leave them scoped-out
> unless the goal becomes true production — explaining *why* you accepted those trade-offs is
> stronger in an interview than half-building them.
