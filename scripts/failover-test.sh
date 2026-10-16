#!/usr/bin/env bash
# Failover test: two backend replicas, one portfolio run, and the replica working it is killed part-way.
#
# Passes only if the surviving replica finishes the run as a second attempt, and does so from the chunks
# the dead one had checkpointed rather than from the beginning. `docker kill` is SIGKILL: no shutdown hook
# runs, so the run is found by its heartbeat going stale (two minutes), put back in the queue by whichever
# replica leads, and claimed from the queue by a replica with a free worker.
#
# Needs JWT_SECRET in the environment, the same one the stack is started with (the script signs a session
# token for the bootstrap admin, as the load test does), and ADMIN_PASSWORD so that admin exists.
# It leaves the stack running; it never removes volumes. Set COMPOSE_PROJECT_NAME to keep it apart from a
# stack you care about.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows would rewrite /dev/null and /v1/... into Windows paths

: "${JWT_SECRET:?JWT_SECRET must be set to the secret the stack runs with}"
CURL_IMAGE=${CURL_IMAGE:-curlimages/curl:8.10.1}
# Loans the first replica must have finished before it is killed: two chunks, so there is something to resume from.
KILL_AFTER_LOANS=${KILL_AFTER_LOANS:-24}

fail() {
  printf 'FAILED: %s\n' "$*" >&2
  docker compose logs --tail 60 backend >&2 || true
  exit 1
}

docker compose --profile app up -d --build --scale backend=2 postgres redis neo4j backend

mapfile -t replicas < <(docker compose ps -q backend | tr -d '\r')
[ "${#replicas[@]}" -eq 2 ] || fail "expected 2 backend replicas, found ${#replicas[@]}"
victim=${replicas[0]}
survivor=${replicas[1]}

healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" | tr -d '\r')" = healthy ]; }
for _ in $(seq 1 90); do
  if healthy "$victim" && healthy "$survivor"; then break; fi
  sleep 5
done
if ! healthy "$victim" || ! healthy "$survivor"; then
  fail "the replicas did not become healthy"
fi

network=$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "$victim" | tr -d '\r' | grep '_edge$')
address() { docker inspect -f "{{(index .NetworkSettings.Networks \"$network\").IPAddress}}" "$1" | tr -d '\r'; }
victim_api="http://$(address "$victim"):8080/v1"
survivor_api="http://$(address "$survivor"):8080/v1"

# A session token for the bootstrap admin (see the load test job in .github/workflows/ci.yml).
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
org=$(docker compose exec -T postgres psql -U arthadhruva -d arthadhruva -Atc "select id from organization where slug='legacy'" | tr -d '\r')
[ -n "$org" ] || fail "the bootstrap organization does not exist (is ADMIN_PASSWORD set?)"
header=$(printf '{"alg":"HS256"}' | b64)
payload=$(printf '{"sub":"admin","role":"ADMIN","org":%s,"sv":0,"auth_time":%s,"iat":%s,"exp":%s}' "$org" "$now" "$now" "$((now + 3600))" | b64)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64)
token="$header.$payload.$signature"

curl_in_network() { docker run --rm --network "$network" "$CURL_IMAGE" -sS --max-time 20 "$@" | tr -d '\r'; }
# GET /csrf answers 401 on purpose, with the double-submit cookie attached.
csrf=$(curl_in_network -o /dev/null -D - "$victim_api/csrf" | sed -n 's/^[Ss]et-[Cc]ookie: XSRF-TOKEN=\([^;]*\).*/\1/p' | head -n 1)
[ -n "$csrf" ] || fail "GET /csrf did not set the XSRF-TOKEN cookie"
api() { curl_in_network -H "Cookie: ad_session=$token; XSRF-TOKEN=$csrf" -H "X-XSRF-TOKEN: $csrf" -H 'Content-Type: application/json' "$@"; }
text_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
number_field() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p"; }

started=$(api -X POST "$victim_api/risk/portfolio/runs")
run=$(printf '%s' "$started" | text_field id)
[ -n "$run" ] || fail "the run was not started: $started"
echo "Run $run started on replica ${victim:0:12}"

# The replica that recorded the run works it. Let it finish a few chunks, then kill it.
done_loans=0
for _ in $(seq 1 120); do
  polled=$(api "$victim_api/risk/portfolio/runs/$run")
  status=$(printf '%s' "$polled" | text_field status)
  done_loans=$(printf '%s' "$polled" | number_field loansDone)
  [ "$status" = RUNNING ] || [ "$status" = QUEUED ] || fail "the run ended before the replica could be killed: $polled"
  if [ "${done_loans:-0}" -ge "$KILL_AFTER_LOANS" ]; then break; fi
  sleep 2
done
[ "${done_loans:-0}" -ge "$KILL_AFTER_LOANS" ] || fail "the run made no progress: $polled"

docker kill "$victim" > /dev/null
killed_at=$(date +%s)
echo "Killed replica ${victim:0:12} with $done_loans loans done; waiting for ${survivor:0:12} to take the run over"

status=RUNNING
for _ in $(seq 1 120); do
  polled=$(api "$survivor_api/risk/portfolio/runs/$run")
  status=$(printf '%s' "$polled" | text_field status)
  if [ "$status" = COMPLETED ] || [ "$status" = FAILED ]; then break; fi
  sleep 5
done
[ "$status" = COMPLETED ] || fail "the run did not complete on the surviving replica: $polled"
attempt=$(printf '%s' "$polled" | number_field attempt)
[ "$attempt" = 2 ] || fail "expected the run to complete as attempt 2, got attempt $attempt: $polled"

resumed=$(docker logs "$survivor" 2>&1 | sed -n "s/.*$run attempt 2 resumes with \([0-9]*\) chunk.*/\1/p" | head -n 1)
[ "${resumed:-0}" -ge 1 ] || fail "the surviving replica did not resume from the dead one's checkpoints"

snapshots=$(api "$survivor_api/risk/portfolio" | grep -o '"scenario":"[A-Z_0-9]*","loans"' | sort -u | wc -l)
[ "$snapshots" -ge 1 ] || fail "the completed run stored no snapshot"

echo "PASSED: run $run completed as attempt 2 on the surviving replica $(( $(date +%s) - killed_at )) s after the kill,"
echo "        resuming from $resumed checkpointed chunk(s) of 8 loans."
