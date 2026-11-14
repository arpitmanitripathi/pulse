#!/usr/bin/env bash
# Deploys Pulse from the developer machine: copies the stack files to the VM, pulls the images built by
# GitHub Actions and restarts what changed. Flyway migrates the database when the app starts.
#
#   deploy/deploy.sh            # images tagged latest (the newest green commit on main)
#   deploy/deploy.sh 1a2b3c4    # a specific commit SHA (full or as tagged), e.g. to roll back
#
# Needs an SSH host alias (default "pulse") in ~/.ssh/config. Override with PULSE_HOST=...
set -euo pipefail

HOST="${PULSE_HOST:-pulse}"
TAG="${1:-latest}"
DIR=/opt/pulse
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "→ copying stack files to $HOST:$DIR"
scp -q "$HERE/docker-compose.prod.yml" "$HERE/Caddyfile" "$HERE/backup.sh" "$HOST:$DIR/"

echo "→ pulling images ($TAG) and restarting"
ssh "$HOST" bash -s -- "$DIR" "$TAG" <<'REMOTE'
set -euo pipefail
cd "$1"
export PULSE_TAG="$2"
chmod 700 backup.sh
docker compose -f docker-compose.prod.yml pull --quiet
docker compose -f docker-compose.prod.yml up -d --remove-orphans
docker image prune -f >/dev/null

site=$(grep '^SITE_ADDRESS=' .env | cut -d= -f2)
for i in $(seq 1 60); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' --resolve "$site:443:127.0.0.1" "https://$site/actuator/health")" = 200 ]; then
    echo "✓ https://$site is up ($PULSE_TAG)"
    docker compose -f docker-compose.prod.yml ps --format 'table {{.Service}}\t{{.Status}}'
    exit 0
  fi
  sleep 3
done
echo "✗ not healthy after 3 minutes; last app log lines:" >&2
docker compose -f docker-compose.prod.yml logs --tail 40 app >&2
exit 1
REMOTE
