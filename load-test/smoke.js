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
  // A smoke run is pass/fail: any failed request or check fails the whole run (exit != 0),
  // which is exactly what a CI gate wants.
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    http_req_duration: ['p(95)<2000'],
  },
};

export function setup() {
  guardNotProduction();
}

export default function () {
  const health = http.get(`${BASE_URL}/actuator/health`, { tags: { name: 'GET /health' } });
  check(health, { 'gateway healthy': (r) => r.status === 200 });

  const { token, email, password } = register();
  check(null, { 'registration yielded a token': () => token !== null });
  if (!token) return; // nothing more to assert without a token; thresholds already fail the run

  // Exercise the login path too, with the credentials we just registered.
  const loggedIn = login(email, password);
  const sessionToken = loggedIn.status === 200 ? String(loggedIn.json('accessToken')) : token;

  const me = http.get(`${BASE_URL}/api/v1/auth/me`, authHeaders(sessionToken));
  check(me, { 'me 200 with bearer': (r) => r.status === 200 });

  const dashboard = http.get(`${BASE_URL}/api/v1/dashboard`, authHeaders(sessionToken));
  check(dashboard, { 'dashboard 200': (r) => r.status === 200 });

  sleep(1);
}
