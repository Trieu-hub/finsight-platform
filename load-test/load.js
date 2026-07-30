// Load test — a realistic user journey under a ramping concurrent load, with SLO thresholds
// that fail the run (non-zero exit) when the stack misses them. This is the capacity/latency
// signal for "production-ready"; it is NOT a correctness test (that is CI's job).
//
// Auth happens ONCE, up front: setup() registers + logs in a small pool of users (one per VU),
// and each iteration reuses a token to create an income transaction (write path) then read the
// dashboard and the transaction list (read path). Reusing tokens is deliberate — register/login
// run bcrypt (intentionally CPU-expensive), so authenticating on every iteration would bottleneck
// on auth-service instead of measuring the business endpoints. This exercises the gateway,
// transaction-service, and dashboard-service under load, plus the Kafka fan-out a write triggers.
//
//   k6 run load-test/load.js
//   BASE_URL=http://localhost:8080 VUS=30 DURATION=2m k6 run load-test/load.js
//   P95_MS=1200 ERROR_RATE=0.02 k6 run load-test/load.js     # loosen SLOs on a small box
//
// NEVER run this against production — it registers users and writes transactions. The guard in
// lib/common.js refuses an https:// or vernfy.com BASE_URL (deploy.md §7.4, the 84k-accounts
// incident). Point it at a local or ephemeral CI stack.
import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  BASE_URL,
  INCOME_CATEGORY_ID,
  JSON_HEADERS,
  guardNotProduction,
  registerAndLogin,
  authHeaders,
  today,
} from './lib/common.js';

const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '1m';
const P95_MS = Number(__ENV.P95_MS || 800);
const ERROR_RATE = Number(__ENV.ERROR_RATE || 0.01);
const CHECK_RATE = Number(__ENV.CHECK_RATE || 0.99);

// Error rate and checks always gate the run (correctness under concurrency). The latency SLO
// is only meaningful on a warmed, perf-representative host — set P95_MS=0 to skip it (the CI
// staging stack is nine JVMs on a shared 2-core runner, where a p95 bound just flakes). Default
// 800ms applies to local / real-environment runs. ERROR_RATE / CHECK_RATE are loosened for CI
// (a shared runner has occasional transient hiccups) but still catch a genuinely broken endpoint,
// which fails ~100% of its own checks and dwarfs any tolerance.
const thresholds = {
  http_req_failed: [`rate<${ERROR_RATE}`],
  checks: [`rate>${CHECK_RATE}`],
};
if (P95_MS > 0) {
  thresholds['http_req_duration'] = [`p(95)<${P95_MS}`];
}

export const options = {
  // Ramp up, hold, ramp down — a gentler shape than slamming to full load, so the numbers
  // reflect steady-state behaviour rather than a cold-start spike.
  stages: [
    { duration: '20s', target: VUS },
    { duration: DURATION, target: VUS },
    { duration: '10s', target: 0 },
  ],
  thresholds,
};

export function setup() {
  guardNotProduction();
  // Fail fast if the stack is not up, rather than reporting a run of 100% errors.
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200) {
    throw new Error(`gateway not healthy at ${BASE_URL} (status ${health.status}) — is the stack up?`);
  }
  // Authenticate a pool of users ONCE, sequentially — one token per VU. The hot loop reuses these
  // so the load falls on the business endpoints, not on repeated bcrypt in register/login.
  const tokens = [];
  for (let i = 0; i < VUS; i++) {
    const t = registerAndLogin();
    if (t) tokens.push(t);
  }
  if (tokens.length === 0) {
    throw new Error('setup could not authenticate any load-test user — check the auth path / stack');
  }
  return { tokens };
}

export default function (data) {
  // Reuse a pre-authenticated token (one per VU; round-robin if a few logins failed in setup).
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const auth = authHeaders(token);

  // Write path: create an income transaction (Salary/INCOME/1 — no wallet required).
  const tx = http.post(
    `${BASE_URL}/api/v1/transactions`,
    JSON.stringify({
      type: 'INCOME',
      amount: 100.5,
      currency: 'USD',
      categoryId: INCOME_CATEGORY_ID,
      transactionDate: today(),
      description: 'k6 load test',
    }),
    { headers: { ...JSON_HEADERS, Authorization: auth.headers.Authorization }, tags: { name: 'POST /transactions' } },
  );
  check(tx, { 'transaction created (201)': (r) => r.status === 201 });

  // Read path: the two views the SPA hits right after a write.
  const dashboard = http.get(`${BASE_URL}/api/v1/dashboard`, { ...auth, tags: { name: 'GET /dashboard' } });
  check(dashboard, { 'dashboard 200': (r) => r.status === 200 });

  const list = http.get(`${BASE_URL}/api/v1/transactions`, { ...auth, tags: { name: 'GET /transactions' } });
  check(list, { 'transactions 200': (r) => r.status === 200 });

  sleep(1);
}
