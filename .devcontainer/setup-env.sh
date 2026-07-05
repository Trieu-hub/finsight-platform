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

cat > .env <<EOF
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

# JWT is RS256 (asymmetric): auth-service signs with the private key, every other service
# verifies with the public key only. Reuse the repo's canonical generator — it appends
# JWT_PRIVATE_KEY= and JWT_PUBLIC_KEY= (base64 DER, one line each) to the .env we just wrote.
bash scripts/gen-jwt-keys.sh >> .env

echo "Generated .env with random demo secrets."
echo "Next:  docker compose -f docker-compose.yml -f docker-compose.codespaces.yml up -d --build"
