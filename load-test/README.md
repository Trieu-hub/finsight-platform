# Load & smoke tests (k6)

Performance and end-to-end smoke tests for FinSight, written for [k6](https://k6.io) (the
Grafana-ecosystem load tester — the same family as the Loki/Tempo/Prometheus stack this repo
already runs). They drive the API through the **gateway** (`:8080`), exercising auth-service,
transaction-service, and dashboard-service together over real HTTP.

| Script | What it does | Use it for |
|---|---|---|
| `smoke.js` | 1 VU, one pass of health → register → login → authenticated read. Strict thresholds (zero failures). | A fast pass/fail gate — before a load run, and as a post-deploy check. |
| `load.js` | Ramping concurrent VUs running register → write a transaction → read dashboard + list. SLO thresholds. | Capacity / latency signal under load. |
| `lib/common.js` | Shared endpoint contract, request helpers, and the **"never hit production"** guard. | Imported by both; no reason to run directly. |

## ⚠️ Never run against production

`load.js` and `smoke.js` **write** — they register users and create transactions. Pointing
them at the live site repeats the incident recorded in
[`docs/deploy.md` §7.4](../docs/deploy.md): *a load test once created ~84k junk accounts on
prod.* `lib/common.js` therefore **refuses** a `BASE_URL` that looks like production (any
`https://…`, `vernfy.com`, or the example domain) unless you set `ALLOW_PROD=true`. Run these
only against a **local** stack or the **ephemeral CI stack** (see below) — never `vernfy.com`.

## Run locally

Bring the stack up first (`docker compose up -d --build`), wait for the gateway to be healthy,
then:

```bash
# Installed k6:
k6 run load-test/smoke.js
BASE_URL=http://localhost:8080 VUS=30 DURATION=2m k6 run load-test/load.js

# No local install — run k6 from its official image (host.docker.internal reaches the host):
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 \
  -v "$PWD/load-test:/scripts" grafana/k6 run /scripts/smoke.js
```

## Configuration (env vars)

| Var | Default | Applies to | Meaning |
|---|---|---|---|
| `BASE_URL` | `http://localhost:8080` | both | Gateway base URL. Trailing slashes trimmed. |
| `CATEGORY_ID` | `1` | `load.js` | Seeded category for the write step (1 = Salary/INCOME). |
| `VUS` | `20` | `load.js` | Peak concurrent virtual users. |
| `DURATION` | `1m` | `load.js` | Hold time at peak (excludes the 20s ramp-up / 10s ramp-down). |
| `P95_MS` | `800` | `load.js` | Fail the run if p95 request latency exceeds this. **`0` disables the latency gate** (CI staging uses this — a shared runner is not perf-representative; error rate + checks still gate). |
| `ERROR_RATE` | `0.01` | `load.js` | Fail the run if the failed-request rate exceeds this (1%). |
| `CHECK_RATE` | `0.99` | `load.js` | Fail the run if the check success rate drops below this. Loosened on CI (a shared runner has transient hiccups); a genuinely broken endpoint still fails ~100% of its checks. |
| `ALLOW_PROD` | *(unset)* | both | Escape hatch for the production guard — do not set it for a real prod URL. |

## In CI

`.github/workflows/staging.yml` stands up the **whole compose stack** on a runner (this is the
project's "staging" — ephemeral, `$0`, no extra server), waits for health, runs `smoke.js` then
`load.js` with small-box SLOs, and tears the stack down. It runs on pull requests that touch the
services / compose / this directory, and nightly. A failed threshold fails the job.
