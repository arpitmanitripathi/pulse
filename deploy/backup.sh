#!/usr/bin/env bash
# Nightly database backup on the VM (cron, see deploy/README.md): a pg_dump in custom format, kept 14 days.
# Restore: see deploy/README.md.
set -euo pipefail

DEST=/var/backups/pulse
cd /opt/pulse
mkdir -p "$DEST"

file="$DEST/pulse-$(date -u +%Y%m%dT%H%MZ).dump"
# Write to a temporary name first, so a failed dump never looks like a good backup.
docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U pulse -d pulse -Fc > "$file.part"
mv "$file.part" "$file"
find "$DEST" -name 'pulse-*.dump' -mtime +13 -delete
echo "$(date -u +%FT%TZ) backup ok: $file ($(du -h "$file" | cut -f1))"
