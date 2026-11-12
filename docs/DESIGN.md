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
| AI | Any OpenAI-compatible Chat Completions API (default: Google Gemini `gemini-3.5-flash-lite`, free tier), set by `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`; behind an `IncidentSummarizer` interface. No key → summaries are skipped, everything else works |
| API docs | springdoc OpenAPI at `/swagger-ui` |
| Errors | RFC 9457 `application/problem+json` |
| Frontend | React 19 + Vite + TypeScript 6.0, React Router, Recharts, Tailwind 4; data loading with small hooks on `fetch` (no extra library) |
| Backend tests | JUnit 5, AssertJ, Testcontainers (Postgres, Redis), WireMock, MockMvc |
| Frontend tests | Vitest + Testing Library |
| Coverage | JaCoCo ≥ 80 % lines on the backend, enforced in CI |
| CI | GitHub Actions: backend `mvn verify`, frontend lint + test + build (Node 24), Docker build |
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
| AI timeout | 10 s; one retry after 30 s, then FAILED (alerting never waits for AI) |
| AI summary length | at most 400 characters, plain text |
| Raw check retention | 7 days |
| Hourly rollup retention | 90 days |
| Status page cache | 30 s in Redis |
| Rate limit, public API | 60 requests / min / IP |
| Rate limit, login, register, password reset | 10 requests / min / IP (shared) |
| Per user | 20 monitors, 5 alert channels, 3 status pages |
| Passwords | 10 characters to 72 bytes, not in the bundled list of common passwords (case-insensitive) |
| Password reset link | valid 30 min, single use; max 3 reset emails per address per hour |
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
| `ai` | `IncidentSummarizer` → `OpenAiCompatibleSummarizer`, `SummaryWorker`, `SummaryTrigger` |
| `stats` | `StatsService`, `StatsController`, `RollupRepository` |
| `statuspage` | `StatusPageController`, `PublicStatusController`, `StatusPageService` (Redis cache) |
| `jobs` | `RollupJob`, `RetentionJob`, `AdvisoryLock` |
| `common` | `ApiExceptionHandler` (problem+json), `ClockConfig` (time is injected so tests control it), `OpenApiConfig` |

## Data model (Flyway)

| Table | Columns |
|---|---|
| `users` | id (UUID), email (unique, lower-case), password_hash, created_at |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked_at |
| `password_reset_tokens` | id, user_id, token_hash (unique), expires_at, used_at, created_at |
| `monitors` | id, user_id, name, url, method (GET/HEAD), interval_s, timeout_ms, expected_status (null), active, **state** (UP/DOWN/UNKNOWN), consecutive_failures, consecutive_successes, **next_check_at**, last_checked_at, **reset_at** (last create / URL change / resume), created_at |
| `check_results` | id, monitor_id, checked_at, success, status_code (null), latency_ms, error_type (NONE/TIMEOUT/DNS/TLS/CONNECTION/STATUS/BLOCKED), error_message |
| `incidents` | id, monitor_id, opened_at, resolved_at (null), **resolution** (RECOVERED / MONITOR_CHANGED, set with resolved_at), first_error_type, **summary**, summary_status (PENDING/DONE/FAILED/SKIPPED), summary_attempts, summary_next_attempt_at |
| `alert_channels` | id, user_id, type (EMAIL/SLACK/DISCORD), target, name, created_at |
| `alert_outbox` | id, incident_id, channel_id, kind (DOWN/RECOVERED), attempts, next_attempt_at, sent_at, last_error · **unique (incident_id, channel_id, kind)** |
| `hourly_rollups` | monitor_id, hour (UTC), checks, failures, latency_p50, latency_p95 (successful checks only; null if none) · primary key (monitor_id, hour) |
| `status_pages` | id, user_id, slug (unique), title, created_at |
| `status_page_monitors` | status_page_id, monitor_id, position |

Indexes: `monitors(next_check_at) WHERE active`, `check_results(monitor_id, checked_at)`, `check_results(checked_at)` (V4, for the jobs),
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
- `DOWN` and successes reach 2 → `UP`, set `resolved_at` and resolution RECOVERED, insert one RECOVERED outbox row per channel.
- `UNKNOWN` and first success → `UP` (no alert).
- DOWN alerts go to every channel the user has; RECOVERED alerts only to channels that got the DOWN alert.
- A result whose URL no longer matches the monitor (changed during the check), or for a paused monitor, is dropped.
- Pausing a monitor, resuming it or changing its URL closes its open incident at that moment with resolution
  MONITOR_CHANGED and no RECOVERED alert: nothing recovered, monitoring stopped or moved.

**E. Alert dispatcher (every 5 s).** Claim up to 50 due unsent outbox rows with `SKIP LOCKED`, add 1 to `attempts`
and push `next_attempt_at` 2 minutes out (a lease), commit → send each on a virtual thread with no transaction open →
`sent_at` on success, otherwise `next_attempt_at = now + 30 s × 2^(attempts−1)` and `last_error`. After 6 attempts it stops.
A RECOVERED alert waits until that channel's DOWN alert was sent or given up, so it never arrives first.
The unique key allows one DOWN and one RECOVERED row per incident and channel. Delivery is at-least-once:
a crash between sending and recording `sent_at` can send that one message again.

**F. AI summary (every 5 s).** If no API key is set, every PENDING incident becomes SKIPPED and nothing is sent.
Otherwise claim up to 10 PENDING incidents with `SKIP LOCKED` and a 1-minute lease (`summary_next_attempt_at`),
commit → send the monitor's name, URL and last 20 checks since the monitor's `reset_at` (times given relative to
when it was marked down) with no transaction open → save the text (max 400
characters) as DONE. On failure retry once after 30 s, then FAILED. Shown on the dashboard only, never on the
public page. The prompt allows only the data given; no guessing about infrastructure; explains what 4xx, 5xx and
each error type mean; and asks for no dates or times (the page shows them in the reader's time zone). The output is plain text
and is always displayed as text, never as HTML.

**G. Public status page.** `GET /api/public/status/{slug}` → page title and, per monitor in the page's order: name,
state (UP/DOWN/UNKNOWN/PAUSED), uptime 24 h / 7 d / 30 d, 90 daily uptime bars, hourly latency for 24 h; plus incidents
of the last 30 days (monitor name, opened, resolved, resolution). Cached in Redis for 30 s; editing or deleting the page,
or changing, pausing, resuming or deleting one of its monitors, clears its cache. Browsers are told not to keep their
own copy (`Cache-Control: no-cache`), so a cleared cache shows at once. No AI text, no URLs, no ids, no error details. If Redis is down the page is built without the cache.

**Stats rules (dashboard and public page).**
- Uptime % = successful checks ÷ all checks, rounded *down* to 2 decimals (100.00 only with zero failures).
  No checks → null. Paused time has no checks, so it does not count.
- Latency p50 / p95 use successful checks only (a timeout is not a latency).
- 24 h: exact, from raw checks in the last 24 h. Series: 24 hourly points.
- 7 d: the last 168 whole UTC hours including the current one. Series: 168 hourly points.
- 30 d (and the 90 bars): whole UTC days including today. Series: daily points.
- Hours older than the last 24 come from `hourly_rollups`, newer ones from raw checks. Combining hours: uptime from
  the summed counts; p50 / p95 as the average of the hourly values weighted by successful checks (an approximation:
  exact percentiles need every raw check, and those are deleted after 7 days).
- Series are dense: a bucket with no checks is present with null values, so charts show the gap.

**H. Jobs (one instance at a time via `pg_try_advisory_lock`, held on its own connection for the whole job).**
Hourly at :05 UTC: roll up every complete hour from the newest hour already rolled up (done again, to catch late
results) to the previous hour, at most 7 days back. Rows are upserted, so running twice gives the same result and a
missed run is repaired by the next one. Daily at 03:00 UTC: delete raw checks older than 7 days (in batches of
10,000 rows, so no single long transaction) and rollups older than 90 days.

## Security

**Authentication.** Passwords are BCrypt hashes. A wrong password and an unknown email return the same
401 and take the same time. Access tokens are HS256 JWTs (15 min). Refresh tokens are 256 random bits,
stored only as a SHA-256 hash, sent as an `httpOnly; Secure; SameSite=Strict; Path=/api/auth` cookie and
rotated on every use. Presenting an already-rotated token ends every session of that user.
Passwords that appear in a bundled list of common passwords are refused at registration and reset.

**Password reset.** `POST /api/auth/forgot-password` always answers 204, whether or not the email has an account,
so it can't be used to find out who is registered; the email is sent in the background. If the account exists, a
256-bit random token (stored only as a SHA-256 hash) is emailed as a link to `/reset-password?token=…`, valid 30 min,
max 3 emails per address per hour. `POST /api/auth/reset-password` checks the new password first, then consumes the
token atomically (single use), sets the new password and ends every session of that user.

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

**Rate limits.** Redis fixed window per IP on login, register, forgot-password and reset-password (10 / min, shared) and `/api/public/**`
(60 / min). Refresh and logout are not limited: the refresh token can't be guessed, and the app calls refresh
on every page load. If Redis is down, requests are allowed rather than blocked.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register` · `/login` · `/refresh` · `/logout` | Auth |
| POST | `/api/auth/forgot-password` · `/reset-password` | Password reset by email link |
| GET | `/api/me` | Current user |
| GET, POST | `/api/monitors` | List with state + 24 h uptime (`uptime24h`, on every monitor response) / Create |
| GET, PATCH, DELETE | `/api/monitors/{id}` | One monitor |
| POST | `/api/monitors/{id}/pause` · `/resume` | Pause / resume |
| GET | `/api/monitors/{id}/checks` | Raw checks, last 24 h, paginated |
| GET | `/api/monitors/{id}/stats?window=24h\|7d\|30d` | Checks, failures, uptime %, p50 / p95 latency, series (default 24h) |
| GET | `/api/incidents?monitorId=&status=open\|resolved` · `/api/incidents/{id}` | Incidents (newest first, max 100) · one incident with its alert deliveries and AI summary |
| GET, POST, DELETE | `/api/alert-channels` (`/{id}`) | Channels (max 5; webhook URLs masked) |
| POST | `/api/alert-channels/{id}/test` | Send a test alert now (200, or 502 with the reason) |
| GET, POST, PATCH, DELETE | `/api/status-pages` (`/{id}`) | Status pages (max 3): slug, title, ordered monitor ids (max 20, your own) |
| GET | `/api/public/status/{slug}` | Public status page data |
| GET | `/actuator/health` | Health |

## Frontend pages
`/login`, `/register`, `/forgot-password`, `/reset-password` · `/monitors` (list: state, 24 h uptime, latency sparkline) · `/monitors/new` ·
`/monitors/:id` (latency chart, uptime, recent checks, incidents; edit, pause/resume, delete) ·
`/incidents/:id` (timeline + AI summary) · `/alerts` (channels + test button) ·
`/status-pages` (create, edit, delete, link to the public page) · public `/s/:slug`.

**Session in the browser.** The access token lives only in memory (a script injected into the page can't
find it in `localStorage`). On load the app calls `/api/auth/refresh`; the httpOnly cookie brings the session
back. An API call that gets 401 refreshes once and retries. Refreshes are single-flight inside a tab and
serialised across tabs with the Web Locks API, because two tabs presenting the same refresh token at once
would trip reuse detection and end every session. The public page `/s/:slug` never touches the session.

**Data.** Logged-in pages reload their data every 30 s. Times are shown in the browser's time zone; the
90 daily bars are UTC days and say so. The AI summary is rendered as plain text (React escapes it; no
raw HTML anywhere).

**Local development.** `npm run dev` in `frontend/` serves the app on :5173 and proxies `/api` to the backend
(`PULSE_API`, default `http://localhost:8080`), so the browser sees one origin, exactly as behind Caddy.

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
| 2026-11-11 | The summarizer speaks the OpenAI-compatible Chat Completions format, Gemini by default | Gemini has a free tier; the same format works for Gemini, Groq, OpenRouter, GitHub Models and Anthropic, so the provider is a config change |
| 2026-11-11 | No API key → incidents are marked SKIPPED (migration V3); old incidents are not summarised later | Pulse must work fully without AI; explaining an old outage is not useful |
| 2026-11-11 | Summaries use a lease column (`summary_next_attempt_at`) instead of holding a row lock during the call | A lock held for a 10 s AI call would block the check processor from resolving that incident |
| 2026-11-12 | Default model `gemini-3.5-flash-lite`, pinned (not a `-latest` alias) | Tested with a real key: `gemini-2.5-flash` is closed to new users; larger Flash models took 10–60 s; this one answered in 1.6–6 s with accurate summaries. A pinned name can't silently change behaviour |
| 2026-11-12 | Latency percentiles use successful checks only | A timeout or refused connection has no meaningful response time; mixing them in would hide real slowness |
| 2026-11-12 | 7 d / 30 d p50 and p95 are hourly percentiles averaged, weighted by successful checks; 24 h is exact | Raw checks are kept 7 days, so exact long-window percentiles are impossible; the weighting keeps busy hours from being outvoted by quiet ones |
| 2026-11-12 | Uptime is rounded down to 2 decimals; null when there were no checks | 99.996 % must not show as 100.00 %; "no data" is different from 0 % |
| 2026-11-12 | 7 d is 168 whole UTC hours, 30 d and the 90 bars are whole UTC days; series include empty buckets | Bars and points line up with clock hours and days; charts can show gaps honestly |
| 2026-11-12 | The rollup job re-rolls from the newest rolled-up hour, upserting | One idempotent rule covers late results, a missed run and a restart |
| 2026-11-12 | New index `check_results(checked_at)` (migration V4) | The rollup and retention jobs select by time across all monitors; without it each run scans the whole table |
| 2026-11-12 | Retention deletes raw checks 10,000 rows per transaction | A day of checks for 1,000 monitors is ~1.4 M rows; one huge delete would hold locks and bloat the WAL |
| 2026-11-12 | Advisory locks are session locks on a connection held for the job's duration | Work inside the job uses other pooled connections; if the instance dies, its connection closes and the lock is freed |
| 2026-11-12 | Status page slugs: 3–40 of `a–z 0–9 -`, not starting or ending with `-`, unique across all users (409 if taken) | The slug is the public address; uniqueness is global |
| 2026-11-12 | A status page may only list the owner's monitors (400 otherwise); its cache is cleared after the change commits | Another user's id must not be usable or probable; clearing before commit could re-cache the old version |
| 2026-11-12 | The public page shows names, state, uptime, latency and incident times only | It is anonymous: URLs, ids, error messages and AI text can reveal internals |
| 2026-11-12 | TypeScript 6.0, not 7 | typescript-eslint supports TypeScript up to 6.0; the linter must understand the compiler's version |
| 2026-11-12 | Access token in memory only; session restored from the refresh cookie on load | A token in `localStorage` can be read by any script that runs on the page |
| 2026-11-12 | Token refresh is single-flight per tab and locked across tabs (Web Locks) | Parallel refreshes with one cookie look like a stolen token and would log the user out everywhere |
| 2026-11-12 | Data loading with small hooks on `fetch`, polling every 30 s; no data-fetching library | Few screens, simple needs; less to learn and explain |
| 2026-11-12 | The monitor list gets each sparkline from `/stats?window=24h` (one call per monitor, max 20) | Reuses the stats API; 20 small calls every 30 s is fine at this scale |
| 2026-11-12 | Monitor editing happens on the monitor's page, status page editing on `/status-pages` | Fewer routes; the form is the same component as for creating |
| 2026-11-12 | The auth rate limit covers login and register only, not refresh or logout | Found in the browser test: every page load refreshes the session, so 10 page loads a minute logged a user out; a 256-bit refresh token can't be guessed, so limiting it protected nothing |
| 2026-11-13 | Incidents record why they closed (`resolution`, migration V5); pausing closes the open incident | Found in manual testing: an incident closed by pause/resume was shown as "Recovered" and, publicly, as "down 5 h" although nothing was being checked |
| 2026-11-13 | Check results for a paused monitor are dropped | A check in flight when the monitor is paused must not reopen an incident on it |
| 2026-11-13 | The AI only gets checks since the monitor's last reset (`monitors.reset_at`, V5), with relative times | Found in manual testing: checks from before a pause were sent, and the summary dated the outage from them |
| 2026-11-13 | The AI prompt explains 4xx vs 5xx and each error type, and forbids dates and times | 6 of 11 real summaries called a 404 a "server error"; UTC times next to local times read as a contradiction |
| 2026-11-13 | Public status responses carry `Cache-Control: no-cache` instead of `max-age=30` | Reproduced in Chrome: after an edit the server returned the new page but the browser kept showing its own 30 s copy. Redis still absorbs the load |
| 2026-11-13 | Changing, pausing, resuming or deleting a monitor clears the cache of every status page that shows it | Edits on the page itself were instant, edits on its monitors took 30 s; now both are instant |
| 2026-11-13 | Common passwords are refused (SecLists list filtered to 10+ characters, MIT licence); no composition rules | NIST SP 800-63B: block known passwords instead of forcing symbols, which produce predictable passwords |
| 2026-11-13 | Forgot password by emailed link (V5 table); answer is identical for unknown emails; reset ends every session | Without it a user who forgets the password is locked out for good. Same response hides who has an account; ending sessions locks out whoever caused the reset |
| 2026-11-13 | Deletes are confirmed in a Pulse dialog, not the browser's `confirm()` | The browser box shows the host name and can't match the app; the dialog focuses Cancel, so Enter never deletes by accident |
| 2026-11-13 | After a 429 on login the form counts down and disables the button | The server's "try again in N seconds" was static text and looked stuck |
| 2026-11-13 | A deliberate log-out forgets the current page; an expired session still returns to it | Logging back in after choosing to log out should start at the dashboard, and the next person on that browser shouldn't land on the previous user's page |
