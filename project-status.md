# FinSight — Project Status

_Last updated: 2026-06-08_
_Repo: `D:\finsight` · This file is kept outside the repo (`C:\Users\pc\project-status.md`) so it stays untracked. (The drive root `D:\` is not writable in this environment.)_

FinSight is a personal-finance microservices platform: JWT auth, user profiles,
transactions, budgets, and a read-only dashboard/BFF, behind an API gateway.

**Stack:** Java 21 · Spring Boot 4.0.6 · Spring Security · Spring Data JPA · Flyway ·
MySQL 8.4 · Redis · JWT (HS512) · Docker Compose · GitHub Actions · Testcontainers.

---

## 1. Architecture (what exists today)

| Service | Port | Owns DB | Responsibility |
|---|---|---|---|
| api-gateway | 8080 | – | Edge routing + JWT validation (HS512/iss/aud), error envelope |
| auth-service | 8081 | `auth_db` | Register, login, refresh, account lockout; Redis-backed tokens |
| user-service | 8082 | `user_db` | User profile data |
| transaction-service | 8083 | `transaction_db` | Transactions, categories, summaries (categories/trend/monthly) |
| budget-service | 8084 | `budget_db` | Budget definitions + utilization |
| dashboard-service | 8085 | none (BFF) | Aggregates user + transaction + budget; fail-fast; relays JWT |
| mysql / redis | – | – | Shared MySQL instance (DB-per-service logical isolation) + Redis |

**Design rules:** no runtime cross-service calls between business services (only the
dashboard BFF calls others); `userId` read only from the JWT; Flyway owns the schema
(`ddl-auto: validate`); shared HMAC secret; gateway is removable (services validate
tokens independently).

---

## 2. Work done in this engineering session

All code changes live on branch **`feature/ci-github-actions`** and are **uncommitted**
except the CI workflow. The dashboard fix (item 6) was actually built and verified live
in Docker; the rest was implemented and statically reviewed but **not compiled/tested
locally** (no local Maven; CI is the intended gate).

1. **Production-readiness review (advisory).** Critiqued the proposed roadmap; identified
   that observability and security were under-prioritized and that "95% complete" measured
   features, not production readiness.

2. **CI pipeline (committed).** `.github/workflows/ci.yml` — matrix build/test of all six
   services on `pull_request` + push to `main`, JDK 21, Maven cache, `mvn verify` (unit +
   Testcontainers integration tests), Surefire reports on failure. README documented.

3. **Secrets & DB hardening.** Removed the hardcoded JWT secret and `root`/`123456` DB
   password from `docker-compose.yml`; moved to a gitignored `.env` (+ `.env.example`
   template). Added a MySQL init script creating **per-service least-privilege DB users**
   (`auth_user`→`auth_db`, etc.). Wrote a JWT secret-rotation runbook
   (`docs/security/jwt-secret-rotation.md`). Added `.gitattributes` (LF for shell scripts).

4. **Health checks / startup reliability.** Enabled Actuator liveness/readiness probes in
   all six services (DB-backed readiness includes `db`; auth includes `redis`; liveness
   excludes infra). Added `curl` to runtime images, compose healthchecks for every service,
   and converted dashboard/gateway `depends_on` to `condition: service_healthy` (fixes the
   startup race). Widened the public Actuator security matcher to `/actuator/health/**`.

5. **JWT validation parity.** Audited all six validators; the four downstream services
   verified only the signature (no algorithm pin, no issuer/audience). Hardened them — and
   converted auth from observe-only to enforce — so **HS512 + issuer (`finsight-auth`) +
   audience (`finsight-api`) + signature + expiry are enforced everywhere**, matching the
   gateway. Added a `JwtServiceTest` per service (valid / wrong issuer / wrong audience /
   wrong algorithm / expired); kept existing fixtures valid.

6. **Dashboard root-cause investigation (built + verified live).** Reproduced the
   `DASHBOARD_UPSTREAM_ERROR` / `SERVICE_TIMEOUT` and proved it is a faithful symptom of
   **transaction-service being unreachable/not-ready**, not a security/routing/header/
   deserialization bug (all verified working at runtime). Fixed two real defects: the
   exception handler logged nothing (added WARN/ERROR logging of the real cause), and the
   dashboard didn't exclude `UserDetailsServiceAutoConfiguration` (the "generated security
   password" red herring — now excluded). Rebuilt the image and verified end-to-end.

---

## 3. What the project has accomplished (capabilities)

- Working end-to-end auth flow: register → login → JWT (HS512) → authorized calls through
  the gateway, with per-account brute-force lockout and Redis-backed token/session data.
- Full CRUD + reporting in transaction and budget services (summaries, trends, utilization).
- A stateless BFF that aggregates three services with fail-fast behavior and JWT relay.
- Flyway-owned schemas, `validate`-only Hibernate, DB-per-service logical isolation.
- Defense-in-depth JWT validation (edge + every service), now consistent.
- Containerized local stack that comes up healthy with readiness-gated startup ordering.
- Automated CI and a meaningful Testcontainers-based integration test suite.

---

## 4. Is this CV-worthy for an intern interview?

**Yes — comfortably above typical intern-portfolio level**, provided it is described
honestly. It demonstrates skills most intern candidates cannot show:

- Real microservice decomposition with an explicit BFF and enforced architectural rules.
- Security depth: JWT with algorithm pinning and issuer/audience enforcement, account
  lockout, secret externalization, least-privilege DB users, and a rotation runbook.
- Operational maturity: Actuator liveness/readiness, Docker healthchecks, dependency
  gating, CI with containerized integration tests.
- **A genuine debugging narrative:** a reproduced, evidence-based root-cause investigation
  with a fix verified at runtime — this is the strongest interview talking point.

**Suggested CV bullet (honest version):**
> Built a 6-service Spring Boot 4 / Java 21 finance platform (API gateway, JWT auth,
> transactions, budgets, dashboard BFF) with MySQL/Flyway/Redis, Dockerized with
> readiness-gated startup, GitHub Actions CI, and Testcontainers integration tests;
> hardened JWT validation (HS512 + issuer/audience) and per-service DB credentials, and
> diagnosed/fixed a distributed upstream-failure bug with live verification.

**Caveats to be honest about in interview:** it is a learning/portfolio project, not
production-deployed; most of this session's hardening is on a feature branch and not yet
merged or run through CI; load/scale and a real deployment target are absent.

---

## 5. What's still missing for production readiness

**Blocking (do before any real deployment)**
- Merge the feature branch and get a **green CI run** — most hardening is uncommitted and
  unverified by the build.
- Replace the **shared symmetric HMAC secret** with asymmetric signing (RS256/ES256): today
  every service can *mint* tokens, not just verify. Single biggest structural risk.
- No real **deployment target** (Kubernetes/ECS manifests), no TLS/HTTPS, no managed
  secrets store (currently a local `.env`).
- Single shared MySQL instance = shared failure domain; no **backup/restore** strategy.

**Observability (largely absent)**
- No correlation-ID propagation, no structured (JSON) logging, no distributed tracing.
- No metrics (Micrometer/Prometheus), no dashboards (Grafana), no alerting.
- Logging is now present on the dashboard error path only; needs to be platform-wide.

**Resilience & API**
- No edge rate limiting; auth endpoints are unthrottled at the gateway.
- No gateway max-request-body limit (custom proxy buffers full bodies in memory).
- No retries/circuit breakers; dashboard fan-out is strictly fail-fast.
- No token revocation/denylist for access tokens.
- No OpenAPI/Swagger contract; no consumer-driven contract tests for the BFF↔upstreams.

**Testing & process**
- No load/performance tests, no whole-stack automated E2E in CI, no security scanning
  (SAST/dependency/secret scanning).
- No staging environment, no rollback/runbook beyond the JWT rotation doc.

---

## 6. Honest status of this session's changes

| Item | State | Verified? |
|---|---|---|
| CI workflow | committed (branch) | YAML structure only; no live CI run |
| Secrets/DB users | uncommitted | `docker compose config` valid; not booted with new users |
| Health probes / compose | uncommitted (running stack has them) | live: stack healthy, readiness-gated |
| JWT parity + tests | uncommitted | static review only; **not compiled/tested locally** |
| Dashboard fix | uncommitted | **built + verified live in Docker** |

**Next concrete step:** split the work onto a cohesive branch (or branches), push, and let
CI compile and run the test suite — that is the first real validation of items 3–5.
