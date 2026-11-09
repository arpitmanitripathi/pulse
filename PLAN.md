# Pulse — Final Build Plan

## Goal
An uptime monitoring service built as a complete backend project:
register a URL → checked every 30–300 s → incident opened after repeated failures →
one alert per incident (email, Slack, Discord) → LLM-written incident summary →
public status page with uptime % and latency. Deployed live on AWS.
Simple enough to explain every part in an interview.

## Rules for building (read before every session)
1. **This file is the source of truth.** If something is not written here, it is not built.
2. **New ideas go to the Parking lot** at the bottom. They are not built until v1 is finished.
3. **A decision changes only by editing this file first**, with the date and the reason, in the Change log.
4. **One milestone at a time, in order.** A milestone is finished only when every Done-when box is ticked.
5. **One branch per milestone** (`m3-scheduler`), merged into `main` through a PR with CI green.
6. **No milestone ends with failing or skipped tests.**

## Final decisions

| Topic | Decision |
|---|---|
| Language | Java 21 (virtual threads) |
| Framework | Spring Boot 4.x, latest stable at M0, version pinned in `pom.xml` |
| Build | Maven |
| Database | PostgreSQL 17, schema managed by Flyway migrations |
| Cache / rate limit | Redis 7 |
| Auth | Email + password (BCrypt). Access JWT (15 min) + refresh token (7 days, httpOnly cookie, stored hashed, rotated on use) |
| HTTP checks | Java `HttpClient` on virtual threads |
| Email | Spring Mail over SMTP. Dev: Mailpit container. Prod: free SMTP provider, chosen at M9 |
| Chat alerts | Slack and Discord incoming webhooks |
| AI | Anthropic API via the official Java SDK, model `claude-haiku-5-5`, behind a `IncidentSummarizer` interface |
| API docs | springdoc OpenAPI at `/swagger-ui` |
| Errors | RFC 9457 `application/problem+json` |
| Frontend | React + Vite + TypeScript, React Router, Recharts, Tailwind |
| Backend tests | JUnit 5, AssertJ, Testcontainers (Postgres, Redis), WireMock, MockMvc |
| Frontend tests | Vitest + Testing Library |
| Coverage | JaCoCo ≥ 80 % lines on the backend, enforced in CI |
| CI | GitHub Actions: backend `mvn verify`, frontend lint + test + build, Docker build |
| Local run | `docker compose up` (app, Postgres, Redis, Mailpit) |
| Deployment | One AWS EC2 instance, Docker Compose, Caddy for HTTPS |
| Load test | Custom Java harness + WireMock target, results in `BENCHMARK.md` |

## Fixed numbers

| Setting | Value |
|---|---|
| Check intervals allowed | 30, 60, 120, 300 s |
| Check timeout | 1–10 s, default 5 s |
| Success | Final status 200–299 (or exactly `expected_status` if set); redirects followed, max 5 |
| Open incident after | 3 failed checks in a row |
| Resolve incident after | 2 successful checks in a row |
| Scheduler tick | every 1 s, claims up to 100 due monitors per tick |
| Max checks in flight | 500 per instance (semaphore) |
| Alert retries | 6 attempts, backoff 30 s → 1 m → 2 m → 4 m → 8 m → 16 m |
| AI input | the last 20 checks of the monitor |
| AI timeout | 10 s, 1 retry, then marked FAILED (alerting never waits for AI) |
| Raw check retention | 7 days |
| Hourly rollup retention | 90 days |
| Status page cache | 30 s in Redis |
| Rate limit, public API | 60 requests / min / IP |
| Rate limit, auth API | 10 requests / min / IP |
| Per user | 20 monitors, 5 alert channels, 3 status pages |
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

## Data model (Flyway)

| Table | Columns |
|---|---|
| `users` | id (UUID), email (unique, lower-case), password_hash, created_at |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked_at |
| `monitors` | id, user_id, name, url, method (GET/HEAD), interval_s, timeout_ms, expected_status (null), active, **state** (UP/DOWN/UNKNOWN), consecutive_failures, consecutive_successes, **next_check_at**, last_checked_at, created_at |
| `check_results` | id, monitor_id, checked_at, success, status_code (null), latency_ms, error_type (NONE/TIMEOUT/DNS/TLS/CONNECTION/STATUS), error_message |
| `incidents` | id, monitor_id, opened_at, resolved_at (null), first_error_type, **summary**, summary_status (PENDING/DONE/FAILED), summary_attempts |
| `alert_channels` | id, user_id, type (EMAIL/SLACK/DISCORD), target, name, created_at |
| `alert_outbox` | id, incident_id, channel_id, kind (DOWN/RECOVERED), attempts, next_attempt_at, sent_at, last_error · **unique (incident_id, channel_id, kind)** |
| `hourly_rollups` | monitor_id, hour, checks, failures, latency_p50, latency_p95 · primary key (monitor_id, hour) |
| `status_pages` | id, user_id, slug (unique), title, created_at |
| `status_page_monitors` | status_page_id, monitor_id, position |

Indexes: `monitors(next_check_at) WHERE active`, `check_results(monitor_id, checked_at)`,
`alert_outbox(next_attempt_at) WHERE sent_at IS NULL`, `incidents(monitor_id, opened_at)`.

## Flows (exactly what happens)

**A. Add monitor.** Validate → SSRF check → insert with `next_check_at = now()`, `state = UNKNOWN`.

**B. Scheduler (each instance, every 1 s).**
`SELECT … FROM monitors WHERE active AND next_check_at <= now() ORDER BY next_check_at LIMIT 100 FOR UPDATE SKIP LOCKED`
→ set `next_check_at = next_check_at + interval` (if that is still in the past, `now() + interval`) → commit → submit each to the Checker.
If an instance dies after claiming, that monitor misses one check. Accepted.

**C. Check.** Resolve DNS → re-run the SSRF check on the resolved IP (stops DNS rebinding) →
send the request → classify the result → hand it to the Incident engine.

**D. Incident engine (one transaction, monitor row locked).**
Insert `check_results` → update counters →
- `UP/UNKNOWN` and failures reach 3 → `DOWN`, insert incident (summary PENDING), insert one DOWN outbox row per channel.
- `DOWN` and successes reach 2 → `UP`, set `resolved_at`, insert one RECOVERED outbox row per channel.
- `UNKNOWN` and first success → `UP` (no alert).

**E. Alert dispatcher (every 5 s).** Claim due unsent outbox rows with `SKIP LOCKED` → send →
`sent_at` on success, otherwise `attempts + 1` and the next backoff. After 6 failures it stops and keeps `last_error`.
The unique key makes a duplicate alert impossible.

**F. AI summary (every 5 s).** Claim PENDING incidents with `SKIP LOCKED` → send the monitor details
and last 20 checks → save the text (max 400 characters). Shown on the dashboard only, never on the public page.
The prompt allows only the data given; no guessing about infrastructure.

**G. Public status page.** `GET /api/public/status/{slug}` → current state, uptime 24 h / 7 d / 30 d,
90 daily bars, hourly latency for 24 h, incidents for 30 days. Built from rollups, cached in Redis for 30 s. No AI text, no URLs.

**H. Jobs (one instance at a time via `pg_try_advisory_lock`).**
Hourly at :05: roll up the previous hour. Daily at 03:00 UTC: delete raw checks > 7 days and rollups > 90 days.
The last 24 h of stats read raw checks; anything older reads rollups.

## SSRF protection
Allowed schemes: `http`, `https`. Ports 80, 443 and 1024–65535.
Blocked after DNS resolution: loopback, private (10/8, 172.16/12, 192.168/16), link-local
(169.254/16, including cloud metadata), CGNAT 100.64/10, 0.0.0.0/8, multicast, and the IPv6 equivalents.
Applied to monitor URLs **and** webhook URLs, both when saved and at request time.

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
| GET | `/api/incidents?monitorId=&status=` · `/api/incidents/{id}` | Incidents + AI summary |
| GET, POST, DELETE | `/api/alert-channels` (`/{id}`) | Channels |
| POST | `/api/alert-channels/{id}/test` | Send a test alert |
| GET, POST, PATCH, DELETE | `/api/status-pages` (`/{id}`) | Status pages |
| GET | `/api/public/status/{slug}` | Public status page data |
| GET | `/actuator/health` | Health |

Every user-owned resource is filtered by the logged-in user; another user's id returns **404**, not 403.

## Frontend pages
`/login`, `/register` · `/monitors` (list: state, 24 h uptime, latency sparkline) · `/monitors/new` ·
`/monitors/:id` (latency chart, uptime, recent checks, incidents) · `/incidents/:id` (timeline + AI summary) ·
`/alerts` (channels + test button) · `/status-pages` · public `/s/:slug`.

## Code structure (key components)

Every request goes Controller (DTO validation) → Service (rules, `@Transactional`) → Repository → PostgreSQL.
CRUD uses Spring Data JPA. The claim queries (`FOR UPDATE SKIP LOCKED`) use `JdbcTemplate` with explicit SQL.
`IncidentStateMachine` is plain Java with no Spring and no database, so it is unit-tested directly.
No database transaction is ever held open during an HTTP check.

| Package | Components |
|---|---|
| `auth` | `AuthController`, `MeController`, `AuthService`, `JwtService`, `UserRepository`, `RefreshTokenRepository` (JWTs are checked by Spring Security's built-in bearer-token filter) |
| `monitor` | `MonitorController`, `MonitorService`, `MonitorRepository` |
| `security` | `SsrfGuard`, `RateLimiter` (Redis fixed window), `SecurityConfig` |
| `check` | `SchedulerLoop`, `MonitorClaimer`, `HttpChecker`, `CheckResultProcessor`, `CheckResultRepository` |
| `incident` | `IncidentStateMachine`, `IncidentService`, `IncidentController`, `IncidentRepository` |
| `alert` | `AlertChannelController`, `AlertOutboxDispatcher`, `AlertSender` → `EmailSender`, `SlackSender`, `DiscordSender` |
| `ai` | `IncidentSummarizer` → `AnthropicSummarizer`, `FakeSummarizer` (tests), `SummaryWorker` |
| `stats` | `StatsService`, `StatsController`, `RollupRepository` |
| `statuspage` | `StatusPageController`, `PublicStatusController`, `StatusPageService` (Redis cache) |
| `jobs` | `RollupJob`, `RetentionJob`, `AdvisoryLock` |
| `common` | `ProblemDetailsHandler`, `Clock` bean (time is injected so tests control it) |

## Deployment shape
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
  docs/         architecture diagram, screenshots
  docker-compose.yml   local stack
  PLAN.md  README.md  BENCHMARK.md
```

## Tests that must exist
- **State machine:** table-driven unit tests for every transition (UNKNOWN/UP/DOWN × success/failure × counter values).
- **Scheduler:** Testcontainers test with **two schedulers on one database**; 200 due monitors; assert each is checked exactly once.
- **Outbox:** duplicate insert rejected; retry backoff timing; RECOVERED is never sent before DOWN.
- **Checker:** WireMock for 200, 503, slow (timeout), redirect chain, and connection refused.
- **SSRF:** every blocked range, IPv6, a hostname resolving to a private IP.
- **AI:** fake summarizer; Anthropic down → incident and alert still created, summary FAILED.
- **API:** auth required; cross-user access returns 404; validation errors return problem+json.
- **Jobs:** rollup values against hand-computed numbers; retention deletes only old rows.
- **Frontend:** monitor list, create form validation, status page render.

## Milestones

| # | Milestone | Done when |
|---|---|---|
| M0 | Setup | Repo + Maven project + compose stack (Postgres, Redis, Mailpit) + Flyway V1 + CI green + README skeleton |
| M1 | Auth | Register, login, refresh rotation, logout; BCrypt; auth rate limit; tests green |
| M2 | Monitors | CRUD, pause/resume, per-user limits, SSRF validator; tests green |
| M3 | Scheduler + checker | `SKIP LOCKED` claim, virtual-thread checks, results stored; two-scheduler test green |
| M4 | Incidents + alerts | State machine, outbox, email/Slack/Discord, retries, test-alert endpoint; tests green |
| M5 | AI summary | Summarizer with timeout and fallback; summary on the incident API; tests green |
| M6 | Stats + status page | Rollups, retention, stats API, public status API with Redis cache + rate limit; tests green |
| M7 | Frontend | All pages above, wired to the API; frontend tests green |
| M8 | Load test | 1,000 monitors at 60 s for 30 min on 1 instance, then 2 instances; `BENCHMARK.md` filled in |
| M9 | Ship | EC2 + Caddy HTTPS live, monitoring PhishLens's live demo, README with screenshots, architecture diagram, demo link |

## Load test (M8)
Target: WireMock with 50 ms latency and a 2 % error rate. 1,000 monitors at 60 s, 30 min.
Measure: scheduler lag (actual check time − scheduled time) p50 / p95 / p99, missed checks %,
CPU and memory, then the same with 2 instances (no double checks). Record the real numbers; no target is promised in advance.

## Out of scope for v1
Keyword/content checks · SSL-expiry checks · TCP/ping/DNS monitors · multiple probe regions ·
teams/organisations · billing · SMS/phone alerts · OAuth login · custom status-page domains ·
mobile app · Kubernetes · microservices · Kafka/RabbitMQ · Prometheus/Grafana.

## Resume entry (numbers filled in from BENCHMARK.md)
**Pulse — Uptime Monitor** | Java, Spring Boot, PostgreSQL, Redis, React, AWS
- Built an uptime monitoring service that checks [N] endpoints per minute on one instance with p95 scheduling delay of [X] s, using Java 21 virtual threads
- Designed incident detection with failure thresholds and exactly-once alerting via a transactional outbox, safe across multiple instances
- Added LLM-written incident summaries that never delay or block alerts

## Change log
| Date | Change | Reason |
|---|---|---|
| 2026-11-10 | Plan created | — |
| 2026-11-10 | JWTs are validated by Spring Security's resource-server support (Nimbus, HS256) instead of a hand-written `JwtAuthFilter` | Less custom security code; the framework's filter is battle-tested |
| 2026-11-10 | Reusing an already-rotated refresh token revokes all of that user's refresh tokens | A reused token means it was probably stolen; standard refresh-token reuse detection |
| 2026-11-10 | Passwords: 10 characters minimum, 72 bytes maximum | BCrypt only reads the first 72 bytes; Spring Security rejects longer input |
| 2026-11-10 | M1 also delivers `GET /api/me`, Swagger UI and the problem+json error handler | Needed to test auth by hand before the frontend exists |

## Parking lot (ideas for after v1, not built now)
-
