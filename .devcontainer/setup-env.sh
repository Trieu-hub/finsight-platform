#!/usr/bin/env bash
# Codespaces postCreate: generate a throwaway .env with random secrets so the stack can boot
# with no manual setup. This is a DEMO environment — the secrets live only in this Codespace
# and .env is gitignored, so they are never committed. AI features stay off (no key needed).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  echo ".env already exists — leaving it untouched."
  exit 0
fi

gen() { openssl rand -base64 24 | tr -d '/+=\n'; }   # DB password (no special chars)
jwt() { openssl rand -base64 64 | tr -d '\n'; }      # >= 256-bit JWT secret

cat > .env <<EOF
JWT_SECRET=$(jwt)
MYSQL_ROOT_PASSWORD=$(gen)
AUTH_DB_PASSWORD=$(gen)
USER_DB_PASSWORD=$(gen)
TRANSACTION_DB_PASSWORD=$(gen)
BUDGET_DB_PASSWORD=$(gen)
RISK_DB_PASSWORD=$(gen)
NOTIFICATION_DB_PASSWORD=$(gen)
ANALYTICS_DB_PASSWORD=$(gen)
FINSIGHT_NARRATOR_AI_ENABLED=false
FINSIGHT_SUMMARIZER_AI_ENABLED=false
LLM_API_KEY=
EOF

echo "Generated .env with random demo secrets."
echo "Next:  docker compose -f docker-compose.yml -f docker-compose.codespaces.yml up -d --build"
