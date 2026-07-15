#!/bin/sh
# Nightly FinSight database backup, with an OPTIONAL off-box copy.
#
# This is the version-controlled source for the script that runs on the prod VPS
# (/root/backup-finsight.sh, cron `0 3 * * *`). It dumps every database from the
# finsight-mysql container, gzips it, keeps the newest N locally, and — if an off-box
# destination is configured — uploads the dump there too, because backups that live
# only on the same VPS are lost with the VPS (docs/deploy.md §7).
#
# Behaviour is unchanged from the original local-only script UNLESS BACKUP_REMOTE is set:
# with it unset the off-box step is skipped entirely, so this is a safe drop-in replacement.
#
# Off-box copy uses rclone (https://rclone.org) so the destination is your choice —
# S3 / Backblaze B2 / Google Drive / an SFTP host / etc. — configured once with
# `rclone config`; this script never hardcodes a provider or a credential.
#
# Configuration (all optional, sensible defaults for the VPS):
#   BACKUP_DIR       local dir for dumps            (default /root/backups)
#   RETAIN           how many dumps to keep         (default 7)
#   MYSQL_CONTAINER  the MySQL container name        (default finsight-mysql)
#   BACKUP_REMOTE    rclone remote+path, e.g.        (default empty => off-box copy skipped)
#                    "b2:my-bucket/finsight" or "gdrive:finsight-backups"
#
# Example cron entry (idempotent install):
#   ( crontab -l 2>/dev/null | grep -v backup-finsight.sh; \
#     echo '0 3 * * * BACKUP_REMOTE=b2:my-bucket/finsight /root/backup-finsight.sh >> /root/backups/backup.log 2>&1' ) | crontab -
set -eu

BACKUP_DIR="${BACKUP_DIR:-/root/backups}"
RETAIN="${RETAIN:-7}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-finsight-mysql}"
BACKUP_REMOTE="${BACKUP_REMOTE:-}"

mkdir -p "$BACKUP_DIR"
FILE="$BACKUP_DIR/finsight-$(date +%F_%H%M).sql.gz"

# Dump all databases. The root password lives inside the container's env, so it is read
# there (never passed on this host's command line, where `ps` would expose it).
docker exec "$MYSQL_CONTAINER" sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases --single-transaction --quick' \
  | gzip > "$FILE"

# Guard against a truncated/empty dump (broken container, wrong password): a real dump of
# these seven DBs is megabytes, so anything under 1 KiB is a failure — drop it and abort so
# the bad run does not evict a good rotation below.
[ "$(stat -c%s "$FILE")" -ge 1024 ] || { rm -f "$FILE"; echo "dump too small, aborting" >&2; exit 1; }
echo "local backup ok: $FILE ($(stat -c%s "$FILE") bytes)"

# Prune old local rotations: keep the newest $RETAIN.
ls -1t "$BACKUP_DIR"/finsight-*.sql.gz | tail -n +$((RETAIN + 1)) | xargs -r rm -f

# ── Off-box copy (optional) ──────────────────────────────────────────────────────────
[ -n "$BACKUP_REMOTE" ] || { echo "BACKUP_REMOTE unset — off-box copy skipped"; exit 0; }
if ! command -v rclone >/dev/null 2>&1; then
  echo "BACKUP_REMOTE set but rclone is not installed — skipping off-box copy" >&2
  exit 1
fi

echo "uploading to $BACKUP_REMOTE ..."
rclone copyto "$FILE" "$BACKUP_REMOTE/$(basename "$FILE")"

# Mirror the local retention off-box: list remote dumps newest-first and delete the excess.
# `lsf` gives bare filenames; rclone has no built-in "keep newest N", so we compute it.
rclone lsf "$BACKUP_REMOTE" 2>/dev/null \
  | grep '^finsight-.*\.sql\.gz$' \
  | sort -r \
  | tail -n +$((RETAIN + 1)) \
  | while IFS= read -r stale; do
      echo "pruning off-box: $stale"
      rclone deletefile "$BACKUP_REMOTE/$stale" || true
    done

echo "off-box copy ok"
