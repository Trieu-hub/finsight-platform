// Shared helpers for the FinSight k6 scripts. Imported by smoke.js and load.js so the
// endpoint contract, the "never hit production" guard, and the request envelope live in
// exactly one place.
import http from 'k6/http';
import { check } from 'k6';

// The stack is reached through the API gateway (:8080 in dev/CI). In prod the gateway is
// un-published behind Caddy, so BASE_URL would be https://<domain> — which the guard below
// refuses on purpose. Override for a local/ephemeral run: BASE_URL=http://localhost:8080.
export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');

// EXPENSE categories are 4–10, INCOME 1–3 (V2__seed_categories.sql). Salary/INCOME/1 is the
// safest write: positive amount, no wallet required (walletId is optional on the DTO).
export const INCOME_CATEGORY_ID = Number(__ENV.CATEGORY_ID || 1);

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Encode the deploy.md §7.4 lesson as code, not just a doc line: "Never run load tests against
// production — a load test once created ~84k junk accounts here." This test WRITES (registers
// users, creates transactions), so pointing it at the live domain would repeat that incident.
// Refuse any BASE_URL that looks like prod unless the operator explicitly opts in.
export function guardNotProduction() {
  const target = BASE_URL.toLowerCase();
  const looksProd =
    target.startsWith('https://') ||
    target.includes('vernfy.com') ||
    target.includes('finsight.example.com');
  if (looksProd && __ENV.ALLOW_PROD !== 'true') {
    throw new Error(
      `refusing to load-test what looks like production (${BASE_URL}). ` +
        `This test registers users and writes transactions. Point BASE_URL at a local or ` +
        `ephemeral stack (e.g. http://localhost:8080). Override only if you truly mean it: ` +
        `ALLOW_PROD=true.`,
    );
  }
}

// A unique registration each call — a reused email returns an error and would poison the run.
// Returns { token, email, password } so callers can also exercise login with the same creds;
// Registration creates the account only — auth-service's /register returns
// {success, message} with NO tokens (see AuthServiceImpl.register). The access token comes
// from a subsequent /login. Returns the credentials so the caller can log in with them.
export function register() {
  // Both email AND username must be globally unique — auth-service rejects a duplicate of either
  // (DuplicateResourceException). `__VU`/`__ITER` alone repeat across runs (and the smoke run
  // collides with the load run in the same CI job), so derive both from the same timestamped
  // stamp. Username is capped at 50 chars (the DTO's @Size max).
  // __VU/__ITER only exist inside VU code — register() is also called from setup() (pooled auth),
  // where referencing them throws. Guard them; uniqueness holds via Date.now() + a wide random.
  const vu = typeof __VU !== 'undefined' ? __VU : 0;
  const iter = typeof __ITER !== 'undefined' ? __ITER : 0;
  const stamp = `${Date.now()}-${vu}-${iter}-${Math.floor(Math.random() * 1e9)}`;
  const email = `loadtest+${stamp}@loadtest.local`;
  const password = 'LoadTest123!';
  const body = JSON.stringify({
    username: `lt${stamp}`.replace(/-/g, '').slice(0, 50),
    email,
    password,
  });
  const res = http.post(`${BASE_URL}/api/v1/auth/register`, body, {
    headers: JSON_HEADERS,
    tags: { name: 'POST /auth/register' },
  });
  check(res, {
    'register 200': (r) => r.status === 200,
    'register success': (r) => r.json('success') === true,
  });
  return { email, password };
}

// Logs in and returns the access token (string), or null if login did not yield one.
export function login(email, password) {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers: JSON_HEADERS, tags: { name: 'POST /auth/login' } },
  );
  check(res, {
    'login 200': (r) => r.status === 200,
    'login returns accessToken': (r) => !!r.json('accessToken'),
  });
  return res.status === 200 ? res.json('accessToken') : null;
}

// The full "get an authenticated session" path: register an account, then log in for a token.
export function registerAndLogin() {
  const { email, password } = register();
  return login(email, password);
}

export function authHeaders(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

// Today as LocalDate (YYYY-MM-DD), the shape transactionDate expects.
export function today() {
  return new Date().toISOString().slice(0, 10);
}
