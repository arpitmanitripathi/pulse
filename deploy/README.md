# Deploying Pulse

Production is one Azure VM running this Compose stack: Caddy (HTTPS + the React app), the app, PostgreSQL and
Redis. Design and reasons: `docs/DESIGN.md`, "Deployment".

| File | Where it runs | What it does |
|---|---|---|
| `docker-compose.prod.yml` | VM, `/opt/pulse` | The stack, with memory limits for a 1 GiB VM |
| `Caddyfile` | VM, mounted into Caddy | HTTPS, security headers, `/api` and `/actuator/health` to the app, the rest is the React app |
| `.env.example` | copied to `/opt/pulse/.env` | Settings and secrets (the real file never leaves the VM) |
| `deploy.sh` | developer machine | Copies the files above, pulls the images, restarts, waits for health |
| `backup.sh` | VM, nightly cron | `pg_dump` to `/var/backups/pulse`, keeps 14 days |

Images come from GitHub Container Registry: `.github/workflows/release.yml` builds them after CI passes on `main`
(tags: commit SHA and `latest`).

## Deploy

```bash
deploy/deploy.sh             # latest
deploy/deploy.sh <sha>       # a specific commit, e.g. to roll back
```

Needs an SSH alias `pulse` in `~/.ssh/config` (host, user `azureuser`, the key file). SSH is allowed only from the
developer's IP; if that changes, update the `Allow-SSH-MyIP` rule of `pulse-vm-nsg` in the Azure portal first.

## First-time setup on the VM

The VM has Docker, a 2 GB swap file, `ufw` (22, 80, 443) and automatic security updates. Then, once:

```bash
sudo mkdir -p /opt/pulse /var/backups/pulse
sudo chown azureuser: /opt/pulse /var/backups/pulse && chmod 700 /var/backups/pulse
# create /opt/pulse/.env from .env.example: secrets from `openssl rand -base64 48`, then
chmod 600 /opt/pulse/.env
echo '15 3 * * * azureuser /opt/pulse/backup.sh >> /opt/pulse/backup.log 2>&1' | sudo tee /etc/cron.d/pulse-backup
```

## Restore a backup

```bash
cd /opt/pulse
docker compose -f docker-compose.prod.yml stop app
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_restore -U pulse -d pulse --clean --if-exists < /var/backups/pulse/pulse-<time>.dump
docker compose -f docker-compose.prod.yml start app
```

## Useful commands (on the VM, in `/opt/pulse`)

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f --tail 100 app
docker stats --no-stream
```
