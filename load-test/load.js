// Load test — a realistic user journey under a ramping concurrent load, with SLO thresholds
// that fail the run (non-zero exit) when the stack misses them. This is the capacity/latency
// signal for "production-ready"; it is NOT a correctness test (that is CI's job).
//
// Journey per iteration: register → create an income transaction (write path) → read the
// dashboard and the transaction list (read path). That exercises the gateway, auth-service,
// transaction-service, and dashboard-service together, plus the Kafka fan-out a write triggers.
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
  register,
  authHeaders,
  today,
} from './lib/common.js';

const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '1m';
const P95_MS = Number(__ENV.P95_MS || 800);
const ERROR_RATE = Number(__ENV.ERROR_RATE || 0.01);

export const options = {
  // Ramp up, hold, ramp down — a gentler shape than slamming to full load, so the numbers
  // reflect steady-state behaviour rather than a cold-start spike.
  stages: [
    { duration: '20s', target: VUS },
    { duration: DURATION, target: VUS },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: [`rate<${ERROR_RATE}`],
    http_req_duration: [`p(95)<${P95_MS}`],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  guardNotProduction();
  // Fail fast if the stack is not up, rather than reporting a run of 100% errors.
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200) {
    throw new Error(`gateway not healthy at ${BASE_URL} (status ${health.status}) — is the stack up?`);
  }
}

export default function () {
  const { token } = register();
  if (!token) {
    sleep(1);
    return;
  }
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
