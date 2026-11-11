# Pulse — Design

Pulse is an uptime monitor. You register a URL; it is checked every 30–300 s; after repeated failures
an incident opens; each alert channel (email, Slack, Discord) gets exactly one DOWN and one RECOVERED
message per incident; an LLM writes a short summary of what went wrong; and a public status page shows
uptime and latency.

This document is the reference for how it works and why. Decisions are recorded at the end.

## Tech stack

| Topic | Choice |
|---|---|
| Language | Java 21 (virtual threads) |
| Framework | Spring Boot 4.x, version pinned in `pom.xml` |
| Build | Maven |
| Database | PostgreSQL 17, schema managed by Flyway migrations |
| Cache / rate limit | Redis 7 |
| Auth | Email + password (BCrypt). Access JWT (15 min) + refresh token (7 days, httpOnly cookie, stored hashed, rotated on use) |
| HTTP checks | Apache HttpClient 5 on virtual threads, with a DNS resolver that only returns SSRF-approved addresses |
| Email | Spring Mail over SMTP. Dev: Mailpit container |
| Chat alerts | Slack and Discord incoming webhooks |
| AI | Anthropic API via the official Java SDK, model `claude-haiku-5-5`, behind an `IncidentSummarizer` interface |
| API docs | springdoc OpenAPI at `/swagger-ui` |
| Errors | RFC 9457 `application/problem+json` |
| Frontend | React + Vite + TypeScript, React Router, Recharts, Tailwind |
| Backend tests | JUnit 5, AssertJ, Testcontainers (Postgres, Redis), WireMock, MockMvc |
| Frontend tests | Vitest + Testing Library |
| Coverage | JaCoCo ≥ 80 % lines on the backend, enforced in CI |
| CI | GitHub Actions: backend `mvn verify`, frontend lint + test + build, Docker build |
| Local run | `docker compose up` (app, Postgres, Redis, Mailpit) |
| Deployment | One AWS EC2 instance, Docker Compose, Caddy for HTTPS |

## Fixed numbers

| Setting | Value |
|---|---|
| Check intervals allowed | 30, 60, 120, 300 s |
| Check timeout | 1–10 s, default 5 s |
| Success | Final status 200–299 (or exactly `expected_status` if set); redirects followed, max 5 (not followed when `expected_status` is 3xx) |
| Latency | Time from start until the response status line arrives (DNS + connect + TLS + server time); the body is never downloaded |
| Open incident after | 3 failed checks in a row |
| Resolve incident after | 2 successful checks in a row |
| Scheduler tick | every 1 s, claims up to 100 due monitors per tick |
| Max checks in flight | 500 per instance (semaphore) |
| Alert retries | 6 attempts; after attempt n wait 30 s × 2^(n−1) (30 s, 1 m, 2 m, 4 m, 8 m), then give up |
| Alert send lease | 2 min (a claimed alert is retried if the sender crashes mid-send) |
| Test alerts | 5 per minute per user |
| AI input | the last 20 checks of the monitor |
| AI timeout | 10 s, 1 retry, then marked FAILED (alerting never waits for AI) |
| Raw check retention | 7 days |
| Hourly rollup retention | 90 days |
| Status page cache | 30 s in Redis |
| Rate limit, public API | 60 requests / min / IP |
| Rate limit, auth API | 10 requests / min / IP |
| Per user | 20 monitors, 5 alert channels, 3 status pages |
| Passwords | 10 characters to 72 bytes |
| Check User-Agent | `PulseMonitor/1.0` |

## Architecture
```
React app (dashboard + public status page)
        │ HTTPS (Caddy)
        ▼
Spring Boot app  (1..N identical instances)
  ├─ API ─────────────── auth, monitors, channels, incidents, status pages
  ├─ Scheduler ───────── every 1 s: claim due monitors (FOR UPDATE SKIP LOCKED)
  ├─ Checker ─────────── HTTP check on a virtual thread
  ├─ Incident engine ─── counters + UP/DOWN state machine
  ├─ Alert dispatcher ── sends alert_outbox rows, retries with backoff
  ├─ AI summarizer ───── fills incidents.summary
  └─ Jobs ────────────── hourly rollup, daily retention (Postgres advisory lock)
        │                 │                 │
   PostgreSQL           Redis        External: checked sites, Anthropic API,
   (all data)       (cache, limits)  SMTP, Slack, Discord
```
One deployable app. No microservices, no message broker: PostgreSQL coordinates the instances.

## Code structure

Every request goes Controller (DTO validation) → Service (rules, `@Transactional`) → Repository → PostgreSQL.
CRUD uses Spring Data JPA. The claim queries (`FOR UPDATE SKIP LOCKED`) use `JdbcTemplate` with explicit SQL.
`IncidentStateMachine` is plain Java with no Spring and no database, so it is unit-tested directly.
No database transaction is ever held open during an HTTP check.

| Package | Components |
|---|---|
| `auth` | `AuthController`, `MeController`, `AuthService`, `JwtService`, `UserRepository`, `RefreshTokenRepository` (JWTs are checked by Spring Security's built-in bearer-token filter) |
| `monitor` | `MonitorController`, `MonitorService`, `MonitorRepository` |
| `security` | `SecurityConfig`, `SsrfGuard`, `HostResolver`, `RateLimiter` + `RateLimitFilter` (Redis fixed window) |
| `check` | `SchedulerLoop`, `MonitorClaimer`, `Checker` → `HttpChecker`, `CheckResultProcessor`, `CheckController` |
| `incident` | `IncidentStateMachine`, `IncidentController` (state changes happen in `check.CheckResultProcessor`) |
| `alert` | `AlertChannelController`, `AlertChannelService`, `AlertOutboxDispatcher`, `AlertSenders` → `EmailSender`, `SlackSender`, `DiscordSender`, `WebhookClient` |
| `ai` | `IncidentSummarizer` → `AnthropicSummarizer`, `FakeSummarizer` (tests), `SummaryWorker` |
| `stats` | `StatsService`, `StatsController`, `RollupRepository` |
| `statuspage` | `StatusPageController`, `PublicStatusController`, `StatusPageService` (Redis cache) |
| `jobs` | `RollupJob`, `RetentionJob`, `AdvisoryLock` |
| `common` | `ApiExceptionHandler` (problem+json), `ClockConfig` (time is injected so tests control it), `OpenApiConfig` |

## Data model (Flyway)

| Table | Columns |
|---|---|
| `users` | id (UUID), email (unique, lower-case), password_hash, created_at |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked_at |
| `monitors` | id, user_id, name, url, method (GET/HEAD), interval_s, timeout_ms, expected_status (null), active, **state** (UP/DOWN/UNKNOWN), consecutive_failures, consecutive_successes, **next_check_at**, last_checked_at, created_at |
| `check_results` | id, monitor_id, checked_at, success, status_code (null), latency_ms, error_type (NONE/TIMEOUT/DNS/TLS/CONNECTION/STATUS/BLOCKED), error_message |
| `incidents` | id, monitor_id, opened_at, resolved_at (null), first_error_type, **summary**, summary_status (PENDING/DONE/FAILED), summary_attempts |
| `alert_channels` | id, user_id, type (EMAIL/SLACK/DISCORD), target, name, created_at |
| `alert_outbox` | id, incident_id, channel_id, kind (DOWN/RECOVERED), attempts, next_attempt_at, sent_at, last_error · **unique (incident_id, channel_id, kind)** |
| `hourly_rollups` | monitor_id, hour, checks, failures, latency_p50, latency_p95 · primary key (monitor_id, hour) |
| `status_pages` | id, user_id, slug (unique), title, created_at |
| `status_page_monitors` | status_page_id, monitor_id, position |

Indexes: `monitors(next_check_at) WHERE active`, `check_results(monitor_id, checked_at)`,
`alert_outbox(next_attempt_at) WHERE sent_at IS NULL`, `incidents(monitor_id, opened_at)`,
and a unique partial index so a monitor has at most one open incident.

## Flows

**A. Add monitor.** Validate → SSRF check → insert with `next_check_at = now()`, `state = UNKNOWN`.

**B. Scheduler (each instance, every 1 s).**
`SELECT … FROM monitors WHERE active AND next_check_at <= now() ORDER BY next_check_at LIMIT 100 FOR UPDATE SKIP LOCKED`
→ set `next_check_at = next_check_at + interval` (if that is still in the past, `now() + interval`) → commit → submit each to the Checker.
If an instance dies after claiming, that monitor misses one check. Accepted.

**C. Check.** Validate the URL → resolve DNS through the SSRF-checking resolver, so the connection can only
go to an approved public address (stops DNS rebinding, and there is no gap between "checked" and "connected") →
send the request without downloading the body → on a redirect, run the same checks on the new URL (max 5) →
classify the result: NONE, TIMEOUT, DNS, TLS, CONNECTION, STATUS or BLOCKED → hand it to the Incident engine.

**D. Incident engine (one transaction, monitor row locked).**
Insert `check_results` → update counters →
- `UP/UNKNOWN` and failures reach 3 → `DOWN`, insert incident (summary PENDING), insert one DOWN outbox row per channel.
- `DOWN` and successes reach 2 → `UP`, set `resolved_at`, insert one RECOVERED outbox row per channel.
- `UNKNOWN` and first success → `UP` (no alert).
- DOWN alerts go to every channel the user has; RECOVERED alerts only to channels that got the DOWN alert.
- A result whose URL no longer matches the monitor (changed during the check) is dropped.

**E. Alert dispatcher (every 5 s).** Claim up to 50 due unsent outbox rows with `SKIP LOCKED`, add 1 to `attempts`
and push `next_attempt_at` 2 minutes out (a lease), commit → send each on a virtual thread with no transaction open →
`sent_at` on success, otherwise `next_attempt_at = now + 30 s × 2^(attempts−1)` and `last_error`. After 6 attempts it stops.
A RECOVERED alert waits until that channel's DOWN alert was sent or given up, so it never arrives first.
The unique key allows one DOWN and one RECOVERED row per incident and channel. Delivery is at-least-once:
a crash between sending and recording `sent_at` can send that one message again.

**F. AI summary (every 5 s).** Claim PENDING incidents with `SKIP LOCKED` → send the monitor details
and last 20 checks → save the text (max 400 characters). Shown on the dashboard only, never on the public page.
The prompt allows only the data given; no guessing about infrastructure.

**G. Public status page.** `GET /api/public/status/{slug}` → current state, uptime 24 h / 7 d / 30 d,
90 daily bars, hourly latency for 24 h, incidents for 30 days. Built from rollups, cached in Redis for 30 s. No AI text, no URLs.

**H. Jobs (one instance at a time via `pg_try_advisory_lock`).**
Hourly at :05: roll up the previous hour. Daily at 03:00 UTC: delete raw checks > 7 days and rollups > 90 days.
The last 24 h of stats read raw checks; anything older reads rollups.

## Security

**Authentication.** Passwords are BCrypt hashes. A wrong password and an unknown email return the same
401 and take the same time. Access tokens are HS256 JWTs (15 min). Refresh tokens are 256 random bits,
stored only as a SHA-256 hash, sent as an `httpOnly; Secure; SameSite=Strict; Path=/api/auth` cookie and
rotated on every use. Presenting an already-rotated token ends every session of that user.

**Authorisation.** Every user-owned resource is filtered by the logged-in user; another user's id
returns **404**, not 403, so ids can't be probed.

**SSRF protection.** Applied to monitor URLs and webhook URLs, when saved and again at request time.
- Scheme `http` or `https`; no `user:password@` part; ports 80, 443 or 1024–65535.
- Every address the host resolves to must be public, following IANA's special-purpose address registries.
  When unsure, refuse.
  - IPv4 blocked: 0.0.0.0/8, loopback 127/8, private (10/8, 172.16/12, 192.168/16), link-local 169.254/16
    (including cloud metadata), CGNAT 100.64/10, 192.0.0/24, documentation (192.0.2/24, 198.51.100/24,
    203.0.113/24), 192.88.99/24, 198.18/15, multicast, 240/4.
  - IPv6 is an allow-list: only global unicast 2000::/3, minus 2001::/23 (IETF assignments, including
    Teredo), 2001:db8::/32 (documentation) and 2002::/16 (6to4). Everything else, including NAT64
    64:ff9b::/96 and IPv4-mapped addresses, is refused.
- A hostname that doesn't resolve is rejected.

**Webhooks.** Slack targets must be `https://hooks.slack.com/services/…`; Discord targets
`https://discord.com/api/webhooks/…` (or `discordapp.com`). They still pass the SSRF guard when saved and when sent.
A webhook URL works like a password, so the API only ever returns it masked.

**Rate limits.** Redis fixed window per IP on `/api/auth/**` and `/api/public/**`. If Redis is down,
requests are allowed rather than blocked.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register` · `/login` · `/refresh` · `/logout` | Auth |
| GET | `/api/me` | Current user |
| GET, POST | `/api/monitors` | List with state + 24 h uptime / Create |
| GET, PATCH, DELETE | `/api/monitors/{id}` | One monitor |
| POST | `/api/monitors/{id}/pause` · `/resume` | Pause / resume |
| GET | `/api/monitors/{id}/checks` | Raw checks, last 24 h, paginated |
| GET | `/api/monitors/{id}/stats?window=24h\|7d\|30d` | Uptime %, p50 / p95 latency, series |
| GET | `/api/incidents?monitorId=&status=open\|resolved` · `/api/incidents/{id}` | Incidents (newest first, max 100) · one incident with its alert deliveries and AI summary |
| GET, POST, DELETE | `/api/alert-channels` (`/{id}`) | Channels (max 5; webhook URLs masked) |
| POST | `/api/alert-channels/{id}/test` | Send a test alert now (200, or 502 with the reason) |
| GET, POST, PATCH, DELETE | `/api/status-pages` (`/{id}`) | Status pages |
| GET | `/api/public/status/{slug}` | Public status page data |
| GET | `/actuator/health` | Health |

## Frontend pages
`/login`, `/register` · `/monitors` (list: state, 24 h uptime, latency sparkline) · `/monitors/new` ·
`/monitors/:id` (latency chart, uptime, recent checks, incidents) · `/incidents/:id` (timeline + AI summary) ·
`/alerts` (channels + test button) · `/status-pages` · public `/s/:slug`.

## Deployment
One domain. Caddy serves the built React files and proxies `/api/*` to the app, so there is no CORS.
GitHub Actions builds the Docker image, pushes it to GitHub Container Registry, then SSHes into EC2 and runs
`docker compose pull && docker compose up -d`. Postgres and Redis run as containers on the same instance, with a volume for Postgres.

## Repository layout
```
pulse/
  backend/      Spring Boot app; packages by feature:
                auth, monitor, check, incident, alert, ai, statuspage, stats, jobs, security, common
  frontend/     React app
  loadtest/     load-test harness
  deploy/       docker-compose.prod.yml, Caddyfile, deploy notes
  docs/         this document, architecture diagram, screenshots
  docker-compose.yml   local stack
  README.md  BENCHMARK.md
```

## Testing strategy
- **State machine:** table-driven unit tests for every transition (UNKNOWN/UP/DOWN × success/failure × counter values).
- **Scheduler:** Testcontainers test with **two schedulers on one database**; 200 due monitors; each is checked exactly once.
- **Outbox:** duplicate insert rejected; retry backoff timing; RECOVERED is never sent before DOWN.
- **Checker:** WireMock for 200, 503, slow (timeout), redirect chain, and connection refused.
- **SSRF:** every blocked range, IPv6, a hostname resolving to a private IP (fake DNS, no internet needed).
- **AI:** fake summarizer; Anthropic down → incident and alert still created, summary FAILED.
- **API:** auth required; cross-user access returns 404; validation errors return problem+json.
- **Jobs:** rollup values against hand-computed numbers; retention deletes only old rows.
- **Frontend:** monitor list, create form validation, status page render.

## Load test method
Target: WireMock with 50 ms latency and a 2 % error rate. 1,000 monitors at 60 s, 30 min.
Measured: scheduler lag (actual check time − scheduled time) p50 / p95 / p99, missed checks %,
CPU and memory, then the same with 2 instances (no double checks). Results go in `BENCHMARK.md`.

## Not in v1
Keyword/content checks · SSL-expiry checks · TCP/ping/DNS monitors · multiple probe regions ·
teams/organisations · billing · SMS/phone alerts · OAuth login · custom status-page domains ·
mobile app · Kubernetes · microservices · Kafka/RabbitMQ · Prometheus/Grafana.

## Decisions

| Date | Decision | Why |
|---|---|---|
| 2026-11-10 | JWTs are validated by Spring Security's resource-server support (Nimbus, HS256), not a hand-written filter | Less custom security code; the framework's filter is battle-tested |
| 2026-11-10 | Reusing an already-rotated refresh token revokes all of that user's refresh tokens | A reused token means it was probably stolen; standard refresh-token reuse detection |
| 2026-11-10 | Passwords: 10 characters minimum, 72 bytes maximum | BCrypt only reads the first 72 bytes; Spring Security rejects longer input |
| 2026-11-10 | `GET /api/me`, Swagger UI and the problem+json error handler ship with auth | Auth can be tried by hand before the frontend exists |
| 2026-11-10 | Changing a monitor's URL, or resuming it, resets it to UNKNOWN with zero counters and checks it right away | Old results describe a different target or an old period; any open incident is closed when this happens |
| 2026-11-10 | The 20-monitor limit is enforced under a row lock on the user (409 when reached) | Two parallel creates at 19 must not end at 21 |
| 2026-11-10 | SSRF guard also rejects `user:password@` URLs, hostnames that don't resolve, and extra reserved ranges | `user@host` is a classic way to disguise the real host; an unresolvable URL is a typo the user should see at once |
| 2026-11-10 | The monitor list's 24 h uptime field comes with the stats work | Uptime is computed from check results and rollups |
| 2026-11-11 | IPv6 targets must be global unicast (2000::/3) minus 2001::/23, 2001:db8::/32 and 2002::/16; IPv4 documentation and 6to4-relay ranges are blocked | An allow-list fails closed for ranges we didn't think of; NAT64, 6to4 and Teredo can wrap a private IPv4 address |
| 2026-11-11 | Checks use Apache HttpClient 5 instead of the JDK `HttpClient` | It accepts a custom DNS resolver, so the checker connects only to addresses the SSRF guard approved; the JDK client always resolves on its own |
| 2026-11-11 | Every redirect target goes through the SSRF guard; redirects are not followed when `expected_status` is 3xx | A public site can redirect to a private address; a user expecting a 301 wants to see the 301 |
| 2026-11-11 | New error type BLOCKED (migration V2) for checks refused by the SSRF guard | Users should see why a check failed; DNS and CONNECTION would be misleading |
| 2026-11-11 | Latency is time to the status line; the response body is never downloaded | Measures what users feel when a site is slow, and a huge or endless body can't tie up the checker |
| 2026-11-11 | `GET /api/monitors/{id}/checks` ships with the checker: newest first, last 24 h, `page`/`size` (max 200) and `hasMore` | Results must be visible as soon as they exist; `hasMore` avoids a count query |
| 2026-11-11 | The scheduler can be switched off with `pulse.scheduler.enabled=false` (off in tests) | Tests drive the scheduler by hand so they are deterministic |
| 2026-11-11 | Alerts are claimed with a 2-minute lease and sent outside any transaction; delivery is at-least-once | Holding a row lock during a 10 s webhook call would block other work; a rare repeat message is better than a lost one |
| 2026-11-11 | RECOVERED goes only to channels that got the DOWN alert, and waits until that DOWN was sent or given up | "Recovered" about an outage you never heard of is noise; it must never arrive before "down" |
| 2026-11-11 | Slack and Discord targets must be on the provider's official webhook host, over HTTPS | The strongest SSRF rule is to allow only the hosts we actually need |
| 2026-11-11 | Webhook URLs are shown masked in API responses | Anyone with the URL can post to the channel |
| 2026-11-11 | Changing a monitor's URL or resuming it closes its open incident without a RECOVERED alert | Nothing recovered: the user changed what is monitored |
| 2026-11-11 | A check result whose URL no longer matches the monitor is dropped | It describes the old target |
| 2026-11-11 | The test-alert endpoint sends directly (not through the outbox), max 5 per minute per user | The user is waiting for an answer; the limit stops it being used to spam an inbox |
| 2026-11-11 | The incidents API ships with incidents (M4), including alert delivery status per channel | Incidents must be visible as soon as they exist; delivery status explains a missing alert |
| 2026-11-11 | The health endpoint does not check the email server | An SMTP outage would mark a working app unhealthy and could get it restarted; alert retries already cover it |
