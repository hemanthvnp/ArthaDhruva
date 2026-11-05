#!/usr/bin/env bash
# Idempotent deploy for a single server. Run it the first time and again for every update:
#   bash deploy/deploy.sh
# It pulls the branch, rebuilds the frontend and images, starts the stack and waits until the backend
# reports healthy. Run by hand over SSH, or by .github/workflows/deploy.yml on every push to `prod`.
#
# A release that never becomes healthy is rolled back: the commit, the backend image and the frontend
# build that were serving before are put back and started again, and the script still exits non-zero,
# so the failed deploy is reported rather than hidden. This relies on migrations being additive
# (expand, then contract in a later release): the previous code must run against the newer schema, and
# Flyway ignores applied migrations it does not know (its default ignore pattern, *:future).
set -euo pipefail
cd "$(dirname "$0")/.."

BRANCH="${DEPLOY_BRANCH:-prod}"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml"

say() { printf '\n==> %s\n' "$*"; }

[ -f .env ] || { echo "Missing .env. Create it with: cp deploy/.env.example .env  (then edit it)" >&2; exit 1; }
set -a
# shellcheck source=/dev/null
. ./.env
set +a
: "${SITE_ADDRESS:?SITE_ADDRESS is not set in .env}"

if [ ! -f secrets/JWT_SECRET ]; then
  say "Generating secrets (first run only)"
  sh scripts/gen-secrets.sh
fi

BACKEND_IMAGE="arthadhruva-backend"
PREV_SHA=""
PREV_IMAGE=""

backend_container() { $COMPOSE ps -q backend | head -n1; }

wait_healthy() {
  local state=starting
  for _ in $(seq 1 90); do
    state="$(docker inspect -f '{{.State.Health.Status}}' "$(backend_container)" 2>/dev/null || echo starting)"
    [ "$state" = healthy ] && return 0
    sleep 5
  done
  return 1
}

# What is serving now, kept so a failed release can be undone without rebuilding anything.
if running="$(backend_container 2>/dev/null)" && [ -n "$running" ]; then
  PREV_SHA="$(git rev-parse HEAD)"
  PREV_IMAGE="$(docker inspect -f '{{.Image}}' "$running")"
  docker tag "$PREV_IMAGE" "$BACKEND_IMAGE:previous"
  rm -rf frontend/dist.previous
  [ -d frontend/dist ] && cp -a frontend/dist frontend/dist.previous
  echo "serving now: $(git rev-parse --short HEAD), image ${PREV_IMAGE#sha256:}" | cut -c1-80
fi

rollback() {
  if [ -z "$PREV_SHA" ]; then
    echo "Nothing to roll back to (first deploy on this server)." >&2
    return 1
  fi
  say "Rolling back to $(git rev-parse --short "$PREV_SHA")"
  git checkout --quiet --detach "$PREV_SHA"
  if [ -d frontend/dist.previous ]; then
    rm -rf frontend/dist
    mv frontend/dist.previous frontend/dist
  fi
  docker tag "$BACKEND_IMAGE:previous" "$BACKEND_IMAGE:latest"
  $COMPOSE --profile app --profile edge up -d --no-build --remove-orphans postgres redis neo4j backend nginx caddy litellm
  if wait_healthy; then
    echo "Rolled back: the previous release is serving again." >&2
  else
    echo "The previous release did not come back healthy either; this needs a person." >&2
  fi
}

say "Updating code from origin/$BRANCH"
git fetch --quiet origin "$BRANCH"
git checkout --quiet "$BRANCH"
git pull --ff-only --quiet origin "$BRANCH"
echo "at $(git rev-parse --short HEAD): $(git log -1 --format=%s)"

say "Building the frontend"
docker run --rm --user "$(id -u):$(id -g)" -e npm_config_cache=/tmp/.npm -e VITE_API_BASE_URL= \
  -v "$PWD/frontend":/app -w /app node:22-bookworm-slim \
  sh -c "npm ci --no-audit --no-fund && npm run build"

say "Building images and starting the stack"
# --pull: build on the current base image, so operating-system security patches arrive with a deploy.
$COMPOSE --profile app --profile edge build --pull backend
$COMPOSE --profile app --profile edge up -d --remove-orphans postgres redis neo4j backend nginx caddy litellm

say "Waiting for the backend to become healthy"
if ! wait_healthy; then
  echo "Backend did not become healthy in time. Last log lines:" >&2
  $COMPOSE logs --tail=40 backend >&2
  rollback || true
  exit 1
fi
rm -rf frontend/dist.previous

say "Checking the public endpoint https://$SITE_ADDRESS/"
for _ in $(seq 1 12); do
  if curl -fsS -o /dev/null --max-time 10 "https://$SITE_ADDRESS/"; then
    echo "OK: https://$SITE_ADDRESS/ is serving."
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 5
done
echo "The stack is up but https://$SITE_ADDRESS/ is not answering yet." >&2
echo "Usually DNS has not propagated or ports 80/443 are blocked; see DEPLOY.md, Troubleshooting." >&2
$COMPOSE logs --tail=30 caddy >&2
exit 1
