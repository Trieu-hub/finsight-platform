// Smoke test — the smallest run that proves the critical path is alive end to end:
// gateway health → register → login → authenticated read. One VU, one iteration, strict
// thresholds. Fast enough to gate a deploy (CD runs this against the ephemeral CI stack and
// again post-deploy), and it fails loudly if any hop of the journey is broken.
//
//   k6 run load-test/smoke.js                    (defaults to http://localhost:8080)
//   BASE_URL=http://localhost:8080 k6 run load-test/smoke.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, guardNotProduction, register, login, authHeaders } from './lib/common.js';

export const options = {
  vus: 1,
  iterations: 1,
  // A smoke run gates CORRECTNESS, not latency: every request must succeed and every check must
  // pass (exit != 0 otherwise). The latency bound is deliberately loose — the CI stack is nine
  // cold JVMs sharing a 2-core runner, so first-request times are seconds; a tight p95 here only
  // produces flaky failures. It exists solely to catch a pathologically hung stack. The real
  // latency SLO lives in load.js (P95_MS, default 800ms) for a warmed, perf-representative run.
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    http_req_duration: ['p(95)<10000'],
  },
};

export function setup() {
  guardNotProduction();
}

export default function () {
  const health = http.get(`${BASE_URL}/actuator/health`, { tags: { name: 'GET /health' } });
  check(health, { 'gateway healthy': (r) => r.status === 200 });

  // Register creates the account (no token), then login yields the access token.
  const { email, password } = register();
  const token = login(email, password);
  check(null, { 'obtained access token from login': () => token !== null });
  if (!token) return; // nothing more to assert without a token; thresholds already fail the run

  const me = http.get(`${BASE_URL}/api/v1/auth/me`, authHeaders(token));
  check(me, { 'me 200 with bearer': (r) => r.status === 200 });

  const dashboard = http.get(`${BASE_URL}/api/v1/dashboard`, authHeaders(token));
  check(dashboard, { 'dashboard 200': (r) => r.status === 200 });

  sleep(1);
}
