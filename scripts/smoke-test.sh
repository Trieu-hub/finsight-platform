#!/usr/bin/env bash
# Post-deploy smoke test for the FinSight prod stack (Path A: single VPS + Caddy).
#
# Verifies the deploy is actually serving, not just that containers started. Exits 0 on
# PASS, non-zero on the first category that fails — so `deploy.sh` can roll back on it.
#
# What it checks (all NON-destructive — no user/data is created):
#   1. every core container reports healthy (or running, for the ones without a healthcheck)
#   2. auth-service publishes a JWK Set with at least one key (JWT signing is live)
#   3. a bogus login is rejected 400/401 — exercises Caddy? no: the gateway -> auth -> DB
#      path end to end (auth must query the DB to reject), proving the chain works
#   4. the public site answers 200 through Caddy + Cloudflare (edge is up)
#
# Optional deeper check (opt-in, needs a pre-existing throwaway account so nothing is
# created here): run with `--full` and SMOKE_EMAIL / SMOKE_PASSWORD set to also log in
# for real and call an authenticated endpoint — proves JWT mint + validate after e.g. a
# key rotation.
#
# Runs ON the VPS. Reaches un-published services through a curl sidecar that shares the
# target container's network namespace (prod publishes only Caddy), so it needs no host
# port mapping. Requires docker + curl + internet (to pull curlimages/curl once).
set -uo pipefail

FAIL=0
note() { printf '  %-40s %s\n' "$1" "$2"; }
fail() { note "$1" "$2"; FAIL=1; }

CURL_IMG="curlimages/curl:latest"
# Run curl inside the network namespace of $1, so localhost:<port> reaches that service.
sidecar() { local c="$1"; shift; docker run --rm --network "container:$c" "$CURL_IMG" "$@"; }

echo "== 1. container health =="
# Services with an actuator/mysqladmin/redis/kafka healthcheck must be 'healthy'.
for c in finsight-mysql finsight-redis finsight-kafka \
         finsight-api-gateway finsight-auth-service finsight-user-service \
         finsight-transaction-service finsight-budget-service finsight-dashboard-service \
         finsight-risk-service finsight-notification-service finsight-analytics-service; do
  h=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' "$c" 2>/dev/null || echo missing)
  if [ "$h" = healthy ]; then note "$c" "healthy"; else fail "$c" "NOT healthy ($h)"; fi
done
# Caddy + the SPA container carry no healthcheck — 'running' is the bar.
for c in finsight-web finsight-caddy; do
  s=$(docker inspect --format '{{.State.Status}}' "$c" 2>/dev/null || echo missing)
  if [ "$s" = running ]; then note "$c" "running"; else fail "$c" "NOT running ($s)"; fi
done

echo "== 2. JWK Set (JWT signing live) =="
kids=$(sidecar finsight-auth-service -s http://localhost:8081/.well-known/jwks.json 2>/dev/null | grep -o '"kid"' | wc -l | tr -d ' ')
if [ "${kids:-0}" -ge 1 ]; then note "jwks keys" "$kids"; else fail "jwks keys" "none served"; fi

echo "== 3. gateway -> auth -> DB (bogus login must be rejected) =="
code=$(sidecar finsight-api-gateway -s -o /dev/null -w '%{http_code}' \
  -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"smoke-nobody@example.invalid","password":"wrong-on-purpose"}' 2>/dev/null || echo 000)
case "$code" in
  400|401) note "bogus login" "rejected $code (OK)";;
  *)       fail "bogus login" "unexpected $code";;
esac

echo "== 4. public edge (Caddy + Cloudflare) =="
# A browser User-Agent: Cloudflare Bot Fight Mode answers 403 to a bare curl UA.
pub=$(curl -s -o /dev/null -w '%{http_code}' \
  -A 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36' \
  https://vernfy.com 2>/dev/null || echo 000)
if [ "$pub" = 200 ]; then note "https://vernfy.com" "200"; else fail "https://vernfy.com" "$pub"; fi

# --- optional deeper end-to-end (opt-in, no data created here) -----------------------
if [ "${1:-}" = "--full" ]; then
  echo "== 5. full auth flow (login + authenticated call) =="
  : "${SMOKE_EMAIL:?--full needs SMOKE_EMAIL (a pre-existing throwaway account)}"
  : "${SMOKE_PASSWORD:?--full needs SMOKE_PASSWORD}"
  tok=$(sidecar finsight-api-gateway -s -X POST http://localhost:8080/api/v1/auth/login \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$SMOKE_EMAIL\",\"password\":\"$SMOKE_PASSWORD\"}" 2>/dev/null \
        | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
  if [ -n "${tok:-}" ]; then
    note "login" "got access token"
    ac=$(sidecar finsight-api-gateway -s -o /dev/null -w '%{http_code}' \
         -H "Authorization: Bearer $tok" \
         'http://localhost:8080/api/v1/transactions?page=1&limit=1' 2>/dev/null || echo 000)
    if [ "$ac" = 200 ]; then note "authenticated GET" "200"; else fail "authenticated GET" "$ac"; fi
  else
    fail "login" "no access token returned"
  fi
fi

echo
if [ "$FAIL" = 0 ]; then echo "SMOKE: PASS"; exit 0; else echo "SMOKE: FAIL"; exit 1; fi
