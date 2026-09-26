#!/usr/bin/env bash
# Idempotent deploy for a single server. Run it the first time and again for every update:
#   bash deploy/deploy.sh
# It pulls the branch, rebuilds the frontend and images, starts the stack and waits until the backend
# reports healthy. Run by hand over SSH, or by .github/workflows/deploy.yml on every push to `prod`.
set -euo pipefail
cd "$(dirname "$0")/.."

BRANCH="${DEPLOY_BRANCH:-prod}"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml"

say() { printf '\n==> %s\n' "$*"; }

[ -f .env ] || { echo "Missing .env. Create it with: cp deploy/.env.example .env  (then edit it)" >&2; exit 1; }
set -a; . ./.env; set +a
: "${SITE_ADDRESS:?SITE_ADDRESS is not set in .env}"

if [ ! -f secrets/JWT_SECRET ]; then
  say "Generating secrets (first run only)"
  sh scripts/gen-secrets.sh
fi

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
$COMPOSE --profile app --profile edge up -d --build --remove-orphans postgres redis neo4j backend nginx caddy

say "Waiting for the backend to become healthy"
for i in $(seq 1 90); do
  state="$(docker inspect -f '{{.State.Health.Status}}' "$($COMPOSE ps -q backend | head -n1)" 2>/dev/null || echo starting)"
  [ "$state" = healthy ] && break
  sleep 5
done
if [ "$state" != healthy ]; then
  echo "Backend did not become healthy in time. Last log lines:" >&2
  $COMPOSE logs --tail=40 backend >&2
  exit 1
fi

say "Checking the public endpoint https://$SITE_ADDRESS/"
for i in $(seq 1 12); do
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
