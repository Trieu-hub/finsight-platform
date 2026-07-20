#!/usr/bin/env bash
# One-command production deploy for FinSight (Path A: single VPS + Caddy + SOPS secrets).
#
# Replaces the manual "ssh in, git pull, prod-compose up -d --build, eyeball it" dance with
# a deterministic, self-verifying deploy that ROLLS BACK on a failed smoke test.
#
#   fetch origin/main  ->  reset the tree to it  ->  build + up (via SOPS)  ->  smoke-test
#        pass  =>  done
#        fail  =>  reset back to the previous commit, rebuild, re-smoke, exit non-zero
#
# Run ON the VPS from the repo root:  ./scripts/deploy.sh
# Deploy a specific ref instead of origin/main:  ./scripts/deploy.sh <git-ref>
#
# Notes:
#   * `git reset --hard` makes the deploy deterministic — the working tree is defined by
#     git, never by leftover local edits. Gitignored files (secrets.env, the age key,
#     Caddy certs) are UNtracked and so are never touched.
#   * Builds are serialized (COMPOSE_PARALLEL_LIMIT=1): 9 JVM builds in parallel can OOM
#     the 8 GB box. Docker layer caching means unchanged services rebuild in seconds.
#   * Secrets stay encrypted: the actual up/build runs through scripts/prod-compose.sh,
#     which wraps `sops exec-env secrets.env "docker compose ..."`.
set -euo pipefail

cd "$(dirname "$0")/.."
export COMPOSE_PARALLEL_LIMIT=1

TARGET_REF="${1:-origin/main}"

log() { printf '\n\033[1m[deploy] %s\033[0m\n' "$*"; }

PREV="$(git rev-parse HEAD)"
log "fetching $TARGET_REF ..."
git fetch origin --prune
TARGET="$(git rev-parse "$TARGET_REF")"

if [ "$PREV" = "$TARGET" ]; then
  log "already at $TARGET — rebuilding + smoke-testing anyway (idempotent)"
else
  log "deploying $PREV -> $TARGET"
fi

deploy_ref() {
  git reset --hard "$1"
  ./scripts/prod-compose.sh up -d --build
}

log "building + starting $TARGET"
deploy_ref "$TARGET"

log "smoke test"
if ./scripts/smoke-test.sh; then
  log "DEPLOY OK — now at $(git rev-parse --short HEAD)"
  exit 0
fi

# --- rollback -----------------------------------------------------------------------
log "SMOKE FAILED — rolling back to $PREV"
deploy_ref "$PREV"
if ./scripts/smoke-test.sh; then
  log "rolled back OK — still at previous good commit $(git rev-parse --short "$PREV")"
else
  log "ROLLBACK ALSO FAILED — manual intervention needed (was $PREV, tried $TARGET)"
fi
exit 1
