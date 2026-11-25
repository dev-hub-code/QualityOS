#!/usr/bin/env bash
# Two backends behind one load balancer, on a copy of the local database.
#
# Starts a throwaway copy of the running database, two backend instances (A and B) that share one JWT key pair, as
# every environment past local does, and an nginx load balancer that alternates between them. Then it checks:
#   - a session signed in on A works on B, and the balancer serves both
#   - a refresh token issued on A rotates on B; reusing the old one on A revokes the family on both
#   - signing out on A ends the session on B
#   - an edit made on A with a stale version is refused on B (optimistic locking is in the database)
#   - the sign-in rate limit is shared: both instances together allow one limit, not one each
#   - every scheduled job runs on one instance at a time, never twice in an interval
# Mail from the copy goes nowhere. Nothing is changed in the running stack's database.
#
# Usage: DRILL_EMAIL=... DRILL_PASSWORD=... scripts/multi-instance-drill.sh    (stack running: docker compose up -d)
#        KEEP=1 ... leaves the copy running afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DRILL_EMAIL:?Set DRILL_EMAIL and DRILL_PASSWORD to an account in the local database}"
: "${DRILL_PASSWORD:?Set DRILL_PASSWORD}"

PROJECT=qualityos
NETWORK=${PROJECT}_default
SOURCE_DB=${PROJECT}-postgres-1
SOURCE_BACKEND=${PROJECT}-backend-1
DRILL=${PROJECT}-multi
WORK=$(mktemp -d)
JOB_INTERVAL=20
RATE_LIMIT=10
DB_APP_USER=${DB_APP_USER:-qualityos_app}
DB_APP_PASSWORD=${DB_APP_PASSWORD:-qualityos_app_local}
DB_MIGRATION_USER=${DB_MIGRATION_USER:-qualityos_migrator}
DB_MIGRATION_PASSWORD=${DB_MIGRATION_PASSWORD:-qualityos_migrator_local}

say() { printf '%s  %s\n' "$(date +%T)" "$*"; }
cleanup() {
  rm -rf "$WORK"
  if [[ ${KEEP:-0} == 1 ]]; then
    say "KEEP=1: remove with: docker rm -f $DRILL-lb $DRILL-a $DRILL-b $DRILL-db && docker volume rm $DRILL-attachments"
    return
  fi
  docker rm -f "$DRILL-lb" "$DRILL-a" "$DRILL-b" "$DRILL-db" >/dev/null 2>&1 || true
  docker volume rm "$DRILL-attachments" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker inspect "$SOURCE_DB" >/dev/null 2>&1 || { echo "The stack is not running: docker compose up -d" >&2; exit 2; }
docker rm -f "$DRILL-lb" "$DRILL-a" "$DRILL-b" "$DRILL-db" >/dev/null 2>&1 || true
docker volume rm "$DRILL-attachments" >/dev/null 2>&1 || true

# A copy of the database, so the drill's jobs and edits never touch the stack's data.
say "Copying the database"
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
docker exec "$SOURCE_DB" pg_dump -U postgres -d qualityos -Fc | docker exec -i "$DRILL-db" pg_restore -U postgres -d qualityos --exit-on-error
docker volume create "$DRILL-attachments" >/dev/null
# The copy brings the stack's job leases with it, some held for hours; clear them so every job can run in the drill.
docker exec "$DRILL-db" psql -U postgres -d qualityos -qc "DELETE FROM scheduled_job_locks"

# One key pair for both, as Secrets Manager gives every task in AWS.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/private.pem" 2>/dev/null
openssl pkey -in "$WORK/private.pem" -pubout -out "$WORK/public.pem"

# Every job every JOB_INTERVAL seconds, starting 5 seconds after startup, so a minute shows several rounds.
jobs=$(grep -A80 '^    # Each job waits initial-delay' backend/src/main/resources/application.yml \
  | grep -oE '^    [a-z-]+:$' | tr -d ' :' | grep -v monitor)
json=$(for job in $jobs; do printf '"%s":{"initial-delay":"PT5S","interval":"PT%dS"},' "$job" "$JOB_INTERVAL"; done)
json="{\"qualityos\":{\"jobs\":{${json}\"monitor\":{\"interval\":\"PT${JOB_INTERVAL}S\"}}}}"

docker inspect "$SOURCE_BACKEND" --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -vE '^(DB_URL|SMTP_HOST|AUTH_RATE_LIMIT_PER_MINUTE|PATH|JAVA_HOME|LANG|LANGUAGE|LC_ALL|JAVA_VERSION)=' > "$WORK/backend.env"
printf 'DB_URL=jdbc:postgresql://%s:5432/qualityos\nSMTP_HOST=drill-no-mail.invalid\nAUTH_RATE_LIMIT_PER_MINUTE=%d\n' \
  "$DRILL-db" "$RATE_LIMIT" >> "$WORK/backend.env"
started=$(docker exec "$DRILL-db" psql -U postgres -d qualityos -Atc "SELECT now()")
for instance in a b; do
  docker run -d --name "$DRILL-$instance" --hostname "$DRILL-$instance" --network "$NETWORK" \
    --env-file "$WORK/backend.env" -e SPRING_APPLICATION_JSON="$json" \
    -e JWT_PRIVATE_KEY="$(cat "$WORK/private.pem")" -e JWT_PUBLIC_KEY="$(cat "$WORK/public.pem")" \
    -v "$DRILL-attachments":/data/attachments -p 127.0.0.1::8080 \
    "$(docker inspect "$SOURCE_BACKEND" --format '{{.Config.Image}}')" >/dev/null
done

cat > "$WORK/nginx.conf" <<NGINX
events {}
http {
  upstream backends { server $DRILL-a:8080; server $DRILL-b:8080; }
  server {
    listen 80;
    location / { proxy_pass http://backends; add_header X-Upstream \$upstream_addr always; }
  }
}
NGINX
port_of() { docker port "$1" "$2/tcp" | head -1 | sed 's/.*://'; }
A=$(port_of "$DRILL-a" 8080)
B=$(port_of "$DRILL-b" 8080)
say "Waiting for both instances"
for port in "$A" "$B"; do
  for i in $(seq 150); do
    curl -fsS "http://127.0.0.1:$port/actuator/health/readiness" >/dev/null 2>&1 && break
    [[ $i == 150 ]] && { say "FAIL: an instance did not start"; docker logs --tail 40 "$DRILL-a"; exit 1; }
    sleep 1
  done
done
docker run -d --name "$DRILL-lb" --network "$NETWORK" -p 127.0.0.1::80 \
  -v "$WORK/nginx.conf":/etc/nginx/nginx.conf:ro nginx:1-alpine >/dev/null
LB=$(port_of "$DRILL-lb" 80)
for _ in $(seq 20); do curl -fsS "http://127.0.0.1:$LB/actuator/health/readiness" >/dev/null 2>&1 && break; sleep 0.5; done
say "A on :$A, B on :$B, balancer on :$LB"

# The checks. Jobs are read from the copy once they have had a minute to run.
checks=0
A=$A B=$B LB=$LB RATE_LIMIT=$RATE_LIMIT python3 -I - <<'PY' || checks=$?
import json, os, sys, time, urllib.request, urllib.error
A, B, LB = (f"http://127.0.0.1:{os.environ[k]}" for k in ("A", "B", "LB"))
LIMIT = int(os.environ["RATE_LIMIT"])
failed = []

def call(base, method, path, body=None, token=None):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode())
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            text = response.read()
            return response.status, (json.loads(text) if text else None), response.headers
    except urllib.error.HTTPError as error:
        text = error.read()
        return error.code, (json.loads(text) if text else None), error.headers

def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        failed.append(what)

def login(base):
    status, body, _ = call(base, "POST", "/api/v1/auth/login",
                           {"email": os.environ["DRILL_EMAIL"], "password": os.environ["DRILL_PASSWORD"]})
    if status != 200:
        sys.exit(f"Sign-in on {base} returned {status}: {body}")
    return body["data"]

print("Sessions")
session = login(A)
check(call(B, "GET", "/api/v1/auth/me", token=session["accessToken"])[0] == 200, "signed in on A, recognised by B")
upstreams, statuses = set(), set()
for _ in range(20):
    status, _, headers = call(LB, "GET", "/api/v1/auth/me", token=session["accessToken"])
    statuses.add(status)
    upstreams.add(headers.get("X-Upstream"))
check(statuses == {200} and len(upstreams) == 2, f"20 requests through the balancer reached {len(upstreams)} instances, all 200")

print("Refresh tokens")
status, rotated, _ = call(B, "POST", "/api/v1/auth/refresh", {"refreshToken": session["refreshToken"]})
check(status == 200, "a refresh token from A rotates on B")
newer = rotated["data"]
check(call(A, "GET", "/api/v1/auth/me", token=newer["accessToken"])[0] == 200, "the rotated access token works on A")
# Within 10 seconds a reused token is taken as two tabs refreshing at once; after that, as a stolen token.
time.sleep(11)
check(call(A, "POST", "/api/v1/auth/refresh", {"refreshToken": session["refreshToken"]})[0] == 401,
      "reusing the old refresh token on A, after the 10 second grace, is refused")
check(call(B, "POST", "/api/v1/auth/refresh", {"refreshToken": newer["refreshToken"]})[0] == 401,
      "and the reuse revokes the newer token on B too")
fresh = login(B)
check(call(A, "POST", "/api/v1/auth/logout", {"refreshToken": fresh["refreshToken"]})[0] == 204, "signed out on A")
check(call(B, "POST", "/api/v1/auth/refresh", {"refreshToken": fresh["refreshToken"]})[0] == 401,
      "the session cannot be refreshed on B")

print("Concurrent edits")
token = login(A)["accessToken"]
status, page, _ = call(A, "GET", "/api/v1/facilities?size=1", token=token)
if page["data"]["items"]:
    facility = page["data"]["items"][0]["id"]
else:
    status, created, _ = call(A, "POST", "/api/v1/facilities",
                              {"code": f"DRILL-{int(time.time())}", "name": "Drill plant", "state": "Maharashtra"}, token)
    if status != 201:
        sys.exit(f"The account has no facility and cannot create one ({status}): {created}")
    facility = created["data"]["id"]
detail = call(A, "GET", f"/api/v1/facilities/{facility}", token=token)[1]["data"]["facility"]
edit = {key: detail.get(key) for key in ("code", "name", "address", "city", "state", "postalCode", "gstin", "version")}
status, _, _ = call(A, "PUT", f"/api/v1/facilities/{facility}", dict(edit, name=detail["name"] + " (A)"), token)
check(status == 200, "A edits a facility")
status, _, _ = call(B, "PUT", f"/api/v1/facilities/{facility}", dict(edit, name=detail["name"] + " (B)"), token)
check(status == 409, f"B's edit from the same version is refused with 409 (got {status})")
check(call(B, "GET", f"/api/v1/facilities/{facility}", token=token)[1]["data"]["facility"]["name"].endswith("(A)"),
      "B reads A's edit")

print("Sign-in rate limit")
# Start in a fresh minute, so the limit's window does not reset partway through.
second = time.gmtime().tm_sec
if second > 40:
    time.sleep(61 - second)
codes = [call((A, B)[i % 2], "POST", "/api/v1/auth/login",
              {"email": f"nobody-{i}@drill.invalid", "password": "wrong-password"})[0] for i in range(2 * LIMIT)]
allowed = sum(code != 429 for code in codes)
check(allowed <= LIMIT and 429 in codes,
      f"{2 * LIMIT} failed sign-ins alternating A and B: {allowed} let through, the limit is {LIMIT} for both together")
PY

say "Letting the jobs run for a minute"
sleep 70
docker exec -i "$DRILL-db" psql -U postgres -d qualityos -v ON_ERROR_STOP=1 -Atq -F '|' -v started="$started" \
  -v interval="$JOB_INTERVAL" > "$WORK/jobs.txt" <<'SQL'
WITH runs AS (
  SELECT job_name, instance, started_at, finished_at,
         lag(started_at) OVER w AS previous_start, lag(finished_at) OVER w AS previous_end
  FROM scheduled_job_executions WHERE started_at >= :'started'::timestamptz
  WINDOW w AS (PARTITION BY job_name ORDER BY started_at))
SELECT job_name, count(*), count(DISTINCT split_part(instance, '-', 3)),
       count(*) FILTER (WHERE started_at < previous_end),
       count(*) FILTER (WHERE started_at - previous_start < make_interval(secs => :interval * 0.9 - 1))
FROM runs GROUP BY job_name ORDER BY job_name;
SQL
echo "Scheduled jobs (runs, instances that ran it, overlapping runs, runs closer than 90% of the interval)"
jobs_failed=0
while IFS='|' read -r job runs instances overlaps early; do
  if [[ $overlaps == 0 && $early == 0 ]]; then mark="ok  "; else mark="FAIL"; jobs_failed=1; fi
  printf '  %s  %-24s %s runs on %s instance(s), %s overlapping, %s early\n' "$mark" "$job" "$runs" "$instances" "$overlaps" "$early"
done < "$WORK/jobs.txt"
[[ $(wc -l < "$WORK/jobs.txt") -ge 10 ]] || { say "FAIL: only $(wc -l < "$WORK/jobs.txt") jobs ran"; jobs_failed=1; }

echo
if [[ $checks == 0 && $jobs_failed == 0 ]]; then say "PASSED"; else say "FAILED: see above"; exit 1; fi
