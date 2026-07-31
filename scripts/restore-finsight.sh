#!/bin/sh
# FinSight restore DRILL — prove a backup actually restores, without touching production.
#
# "A backup you have never restored is not a backup." The 2026-07-20 incident (docs/runbook.md)
# showed a healthy-looking container can hide a broken backup for days. This script takes one of
# the gzipped dumps produced by backup-finsight.sh and restores it into a THROWAWAY MySQL
# container — never the live finsight-mysql — then verifies every expected database and its
# schema came back. The live database and its volume are never touched; the scratch container is
# always removed on exit. Safe to run any time, including on the prod VPS.
#
# Usage (same locally and on prod):
#   scripts/restore-finsight.sh                    # drill the newest dump in $BACKUP_DIR
#   scripts/restore-finsight.sh /path/to/dump.gz   # drill a specific dump
#
# Exit 0 = restore verified. Non-zero = the backup did NOT restore cleanly — investigate now,
# not the day you actually need it.
#
# Configuration (env, all optional):
#   BACKUP_DIR      where dumps live               (default /root/backups, matches backup-finsight.sh)
#   MYSQL_IMAGE     image for the scratch DB       (default mysql:8.4, matches docker-compose.yml)
#   KEEP_CONTAINER  leave the scratch DB running   (default empty => always torn down)
set -eu

BACKUP_DIR="${BACKUP_DIR:-/root/backups}"
MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:8.4}"

command -v docker >/dev/null 2>&1 || { echo "docker not found on PATH" >&2; exit 1; }

# Pick the dump: an explicit path wins, otherwise the newest rotation in BACKUP_DIR.
if [ "$#" -ge 1 ]; then
  DUMP="$1"
else
  DUMP="$(ls -1t "$BACKUP_DIR"/finsight-*.sql.gz 2>/dev/null | head -n1 || true)"
fi
[ -n "${DUMP:-}" ] && [ -f "$DUMP" ] || {
  echo "no backup file to drill (looked for finsight-*.sql.gz in $BACKUP_DIR)" >&2; exit 1; }
echo "restore drill of: $DUMP ($(stat -c%s "$DUMP" 2>/dev/null || echo '?') bytes)"

# The seven databases a healthy --all-databases dump must contain (see docker/mysql/init).
DATABASES="auth_db user_db transaction_db budget_db risk_db notification_db analytics_db"

# Random name + password: repeated/parallel runs never collide, nothing sensitive is reused.
# No host port is published — we exec into the container over Docker's own channel.
SCRATCH="finsight-restore-drill-$$"
SCRATCH_PW="drill-$(date +%s)-$$"
OUT="$(mktemp)"; ERR="$(mktemp)"

cleanup() {
  rm -f "$OUT" "$ERR"
  if [ -n "${KEEP_CONTAINER:-}" ]; then
    echo "KEEP_CONTAINER set — leaving $SCRATCH running (remove it with: docker rm -f $SCRATCH)"
  else
    docker rm -f "$SCRATCH" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

echo "starting throwaway MySQL ($MYSQL_IMAGE) as $SCRATCH ..."
docker run -d --name "$SCRATCH" -e MYSQL_ROOT_PASSWORD="$SCRATCH_PW" "$MYSQL_IMAGE" >/dev/null

# Wait until it accepts an authenticated query. NOTE: `mysqladmin ping` returns exit 0 even on
# access-denied (the 2026-07-20 lesson), so readiness is a real `SELECT 1`, not a ping. The
# password is passed via MYSQL_PWD (env, inside the container) so it never lands in `ps`.
printf 'waiting for scratch MySQL to become ready'
i=0
until docker exec -e MYSQL_PWD="$SCRATCH_PW" "$SCRATCH" mysql -uroot -N -e 'SELECT 1' >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -gt 60 ] && { echo; echo "scratch MySQL did not become ready in ~120s" >&2; cat "$ERR" >&2 || true; exit 1; }
  printf '.'; sleep 2
done
echo ' ready'

# Verification runs in the SAME session as the restore, appended after the dump. This matters:
# a --all-databases dump also restores the `mysql` grant tables, so a *new* connection afterwards
# would need the production root password (which this drill does not have). Staying in one session
# sidesteps that. The checks use information_schema only, so a missing database yields count 0
# instead of aborting mysql — letting us report per-database PASS/FAIL rather than a bare error.
DBLIST="'auth_db','user_db','transaction_db','budget_db','risk_db','notification_db','analytics_db'"
VERIFY_SQL="
SELECT '__VERSION__', VERSION(), 0;
SELECT '__TABLES__', s.name,
  (SELECT COUNT(*) FROM information_schema.tables t WHERE t.table_schema = s.name)
FROM (
  SELECT 'auth_db' AS name UNION ALL SELECT 'user_db' UNION ALL SELECT 'transaction_db'
  UNION ALL SELECT 'budget_db' UNION ALL SELECT 'risk_db' UNION ALL SELECT 'notification_db'
  UNION ALL SELECT 'analytics_db'
) s;
SELECT '__BYTES__', table_schema, COALESCE(SUM(data_length + index_length), 0)
FROM information_schema.tables
WHERE table_schema IN ($DBLIST)
GROUP BY table_schema;
"

echo "restoring dump into $SCRATCH and verifying ..."
START=$(date +%s)
# Pipeline exit status is that of the last command (mysql) in POSIX sh: a SQL error in the dump
# aborts mysql non-zero and is caught here as a failed restore.
if { gzip -dc "$DUMP"; printf '%s\n' "$VERIFY_SQL"; } \
     | docker exec -i -e MYSQL_PWD="$SCRATCH_PW" "$SCRATCH" mysql -uroot -N >"$OUT" 2>"$ERR"; then
  restore_ok=1
else
  restore_ok=0
fi
ELAPSED=$(( $(date +%s) - START ))

if [ "$restore_ok" -ne 1 ]; then
  echo "RESTORE FAILED — mysql reported an error while applying the dump (${ELAPSED}s):" >&2
  sed 's/^/  /' "$ERR" >&2
  exit 1
fi

VERSION="$(awk -F'\t' '$1=="__VERSION__"{print $2}' "$OUT")"
echo "restore applied in ${ELAPSED}s (scratch MySQL ${VERSION:-unknown})"
echo
echo "database        tables   data-KiB"
echo "--------------  ------   --------"
failures=0
for db in $DATABASES; do
  tcount="$(awk -F'\t' -v d="$db" '$1=="__TABLES__" && $2==d {print $3}' "$OUT")"
  bcount="$(awk -F'\t' -v d="$db" '$1=="__BYTES__"  && $2==d {print $3}' "$OUT")"
  tcount="${tcount:-0}"; bcount="${bcount:-0}"
  if [ "$tcount" -gt 0 ]; then status="ok"; else status="MISSING"; failures=$((failures + 1)); fi
  printf '%-14s  %6s   %8s  %s\n' "$db" "$tcount" "$((bcount / 1024))" "$status"
done
echo
echo "(data-KiB is on-disk size from information_schema — a data-present signal, not a row count)"

if [ "$failures" -ne 0 ]; then
  echo "DRILL FAILED — $failures database(s) missing or empty of schema after restore." >&2
  exit 1
fi
echo "DRILL PASSED — all 7 databases restored with schema from $(basename "$DUMP")."
