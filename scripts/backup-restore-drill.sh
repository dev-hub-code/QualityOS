#!/usr/bin/env bash
# Backup and restore drill for the local Docker stack.
#
#   1. Backs up the running database (pg_dump, custom format) and the attachments volume.
#   2. Restores both into throwaway containers: a fresh Postgres with the same roles, and a new volume.
#   3. Checks the copy: every table's row count, the Flyway version, row-level security, every attachment's checksum.
#   4. Starts a second backend against the copy, waits for readiness and, given DRILL_EMAIL and DRILL_PASSWORD,
#      signs in.
#   5. Reports how long the backup and the restore took, then removes the throwaway containers.
#
# The backup stays in BACKUP_DIR (default ./backups, git-ignored). It holds real data, password hashes included:
# keep it as private as the database.
#
# Usage: scripts/backup-restore-drill.sh            (stack running: docker compose up -d)
#        DRILL_EMAIL=... DRILL_PASSWORD=... scripts/backup-restore-drill.sh
#        KEEP=1 scripts/backup-restore-drill.sh     (leave the copy running to look at)
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT=qualityos
NETWORK=${PROJECT}_default
SOURCE_DB=${PROJECT}-postgres-1
SOURCE_BACKEND=${PROJECT}-backend-1
ATTACHMENTS=${PROJECT}_attachments-data
DRILL=${PROJECT}-drill
STAMP=$(date +%Y%m%dT%H%M%S)
OUT=${BACKUP_DIR:-./backups}/$STAMP
DB_APP_USER=${DB_APP_USER:-qualityos_app}
DB_APP_PASSWORD=${DB_APP_PASSWORD:-qualityos_app_local}
DB_MIGRATION_USER=${DB_MIGRATION_USER:-qualityos_migrator}
DB_MIGRATION_PASSWORD=${DB_MIGRATION_PASSWORD:-qualityos_migrator_local}
FAILED=0

say() { printf '%s  %s\n' "$(date +%T)" "$*"; }
fail() { say "FAIL: $*"; FAILED=1; }
now() { date +%s.%N; }
since() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%.1f", b - a }'; }
psql_in() { local container=$1; shift; docker exec -i "$container" psql -U postgres -d qualityos -v ON_ERROR_STOP=1 -Atq "$@"; }

cleanup() {
  if [[ ${KEEP:-0} == 1 ]]; then
    say "KEEP=1: $DRILL-db and $DRILL-backend are still running; remove them with: docker rm -f $DRILL-db $DRILL-backend && docker volume rm $DRILL-attachments"
    return
  fi
  docker rm -f "$DRILL-backend" "$DRILL-db" >/dev/null 2>&1 || true
  docker volume rm "$DRILL-attachments" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker inspect "$SOURCE_DB" >/dev/null 2>&1 || { echo "The stack is not running: docker compose up -d" >&2; exit 2; }
docker rm -f "$DRILL-backend" "$DRILL-db" >/dev/null 2>&1 || true
docker volume rm "$DRILL-attachments" >/dev/null 2>&1 || true
mkdir -p "$OUT"
chmod 700 "$OUT"

# 1. Backup ---------------------------------------------------------------------------------------------------------
say "Backing up the database and attachments to $OUT"
started=$(now)
docker exec "$SOURCE_DB" pg_dump -U postgres -d qualityos -Fc > "$OUT/qualityos.dump"
docker run --rm -v "$ATTACHMENTS":/data:ro -v "$(cd "$OUT" && pwd)":/out alpine:3 \
  sh -c 'tar czf /out/attachments.tgz -C /data . && cd /data && find . -type f -exec sha256sum {} + | sort -k2' \
  > "$OUT/attachments.sha256"
psql_in "$SOURCE_DB" -c "SELECT max(version::int) FROM flyway_schema_history WHERE success" > "$OUT/flyway.version"
backup_seconds=$(since "$started")
(cd "$OUT" && sha256sum qualityos.dump attachments.tgz > SHA256SUMS)
say "Backup took ${backup_seconds}s: database $(du -h "$OUT/qualityos.dump" | cut -f1), attachments $(du -h "$OUT/attachments.tgz" | cut -f1), $(wc -l < "$OUT/attachments.sha256") files"

# 2. Restore into a fresh database and volume -----------------------------------------------------------------------
say "Restoring into $DRILL-db"
started=$(now)
docker run -d --name "$DRILL-db" --network "$NETWORK" \
  -e POSTGRES_DB=qualityos -e POSTGRES_PASSWORD="$(head -c 18 /dev/urandom | base64)" \
  -e DB_APP_USER="$DB_APP_USER" -e DB_APP_PASSWORD="$DB_APP_PASSWORD" \
  -e DB_MIGRATION_USER="$DB_MIGRATION_USER" -e DB_MIGRATION_PASSWORD="$DB_MIGRATION_PASSWORD" \
  -v "$PWD/docker/postgres/init":/docker-entrypoint-initdb.d:ro \
  "$(docker inspect "$SOURCE_DB" --format '{{.Config.Image}}')" >/dev/null
for _ in $(seq 60); do
  docker exec "$DRILL-db" pg_isready -h 127.0.0.1 -U postgres -d qualityos >/dev/null 2>&1 && break
  sleep 1
done
(cd "$OUT" && sha256sum -c --quiet SHA256SUMS) || fail "the backup files do not match their checksums"
docker exec -i "$DRILL-db" pg_restore -U postgres -d qualityos --exit-on-error < "$OUT/qualityos.dump"
docker volume create "$DRILL-attachments" >/dev/null
docker run --rm -v "$DRILL-attachments":/data -v "$(cd "$OUT" && pwd)":/in:ro alpine:3 tar xzf /in/attachments.tgz -C /data
restore_seconds=$(since "$started")
say "Database and files restored in ${restore_seconds}s"

# 3. Check the copy against the source ------------------------------------------------------------------------------
# The stack keeps running during the drill, so a table written to after the dump can differ: run it when idle.
say "Comparing every table"
counts="SELECT format('SELECT %L || '' '' || count(*) FROM %I.%I;', c.relname, n.nspname, c.relname)
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind IN ('r','p') AND n.nspname = 'public' ORDER BY c.relname"
psql_in "$SOURCE_DB" -c "$counts" | psql_in "$SOURCE_DB" > "$OUT/source.counts"
psql_in "$DRILL-db" -c "$counts" | psql_in "$DRILL-db" > "$OUT/restored.counts"
if diff -u "$OUT/source.counts" "$OUT/restored.counts" > "$OUT/counts.diff"; then
  say "All $(wc -l < "$OUT/source.counts") tables match, $(awk '{s+=$2} END {print s}' "$OUT/restored.counts") rows"
else
  fail "row counts differ (see $OUT/counts.diff; written to during the drill?)"
fi
rls="SELECT string_agg(relname, ',' ORDER BY relname) FROM pg_class
     WHERE relrowsecurity AND relforcerowsecurity AND relnamespace = 'public'::regnamespace"
[[ $(psql_in "$SOURCE_DB" -c "$rls") == $(psql_in "$DRILL-db" -c "$rls") ]] \
  && say "Row-level security is on for the same $(psql_in "$DRILL-db" -c "SELECT count(*) FROM pg_class WHERE relrowsecurity AND relnamespace = 'public'::regnamespace") tables" \
  || fail "row-level security differs"
owner="SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'"
[[ $(psql_in "$DRILL-db" -c "$owner") == "$DB_MIGRATION_USER" ]] \
  && say "Tables are owned by $DB_MIGRATION_USER, so the application role cannot bypass row-level security" \
  || fail "tables are not owned by $DB_MIGRATION_USER"
restored_version=$(psql_in "$DRILL-db" -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")
[[ $restored_version == "$(cat "$OUT/flyway.version")" ]] && say "Schema at Flyway version $restored_version" \
  || fail "Flyway version $restored_version, expected $(cat "$OUT/flyway.version")"
docker run --rm -v "$DRILL-attachments":/data:ro alpine:3 sh -c 'cd /data && find . -type f -exec sha256sum {} + | sort -k2' \
  | diff -q - "$OUT/attachments.sha256" >/dev/null \
  && say "All $(wc -l < "$OUT/attachments.sha256") attachments match their checksums" || fail "attachments differ"

# 4. Run the application against the copy ---------------------------------------------------------------------------
# Same image and settings as the running backend, pointed at the copy. Mail goes nowhere, so reminders the copy
# sends do not reach anyone.
say "Starting a backend on the copy"
started=$(now)
docker inspect "$SOURCE_BACKEND" --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -vE '^(DB_URL|SMTP_HOST|PATH|JAVA_HOME|LANG|LANGUAGE|LC_ALL|JAVA_VERSION)=' > "$OUT/backend.env"
printf 'DB_URL=jdbc:postgresql://%s:5432/qualityos\nSMTP_HOST=drill-no-mail.invalid\n' "$DRILL-db" >> "$OUT/backend.env"
docker run -d --name "$DRILL-backend" --network "$NETWORK" --env-file "$OUT/backend.env" \
  -v "$DRILL-attachments":/data/attachments -p 127.0.0.1::8080 \
  "$(docker inspect "$SOURCE_BACKEND" --format '{{.Config.Image}}')" >/dev/null
rm -f "$OUT/backend.env"
port=$(docker port "$DRILL-backend" 8080/tcp | head -1 | sed 's/.*://')
ready=0
for _ in $(seq 120); do
  if curl -fsS "http://127.0.0.1:$port/actuator/health/readiness" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [[ $ready == 1 ]]; then
  say "Backend ready on the copy in $(since "$started")s (Flyway found nothing to migrate)"
else
  fail "the backend did not become ready on the copy"; docker logs --tail 40 "$DRILL-backend"
fi
if [[ $ready == 1 && -n ${DRILL_EMAIL:-} && -n ${DRILL_PASSWORD:-} ]]; then
  status=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "$(printf '{"email":"%s","password":"%s"}' "$DRILL_EMAIL" "$DRILL_PASSWORD")" \
    "http://127.0.0.1:$port/api/v1/auth/login")
  [[ $status == 200 ]] && say "Signed in to the copy as $DRILL_EMAIL" || fail "sign-in on the copy returned $status"
fi

# 5. Report ---------------------------------------------------------------------------------------------------------
echo
if [[ $FAILED == 0 ]]; then
  say "PASSED. Backup ${backup_seconds}s, restore ${restore_seconds}s, plus the backend's start. Backup kept in $OUT"
else
  say "FAILED: see above. Backup kept in $OUT"
fi
exit $FAILED
