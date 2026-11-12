# Pulse

Uptime monitoring with incident detection, one alert per incident, LLM-written incident summaries
and public status pages.

> Work in progress. How it works and why: [docs/DESIGN.md](docs/DESIGN.md).

## Status

| Milestone | State |
|---|---|
| M0 Setup | ✅ |
| M1 Auth | ✅ |
| M2 Monitors | ✅ |
| M3 Scheduler + checker | ✅ |
| M4 Incidents + alerts | ✅ |
| M5 AI summary | ✅ |
| M6 Stats + status page | ✅ |
| M7 Frontend | ✅ |
| M8 Load test | ⏳ |
| M9 Ship | ⏳ |

## Run it locally

Requirements: Docker, Java 21.

```bash
docker compose up --build        # app on :8080 (PULSE_PORT=8081 to change), Postgres :5433, Redis :6380, Mailpit :8025
curl localhost:8080/actuator/health
```

API docs and a try-it-out console: http://localhost:8080/swagger-ui
(register, log in, then click **Authorize** and paste the access token).

The web app (Node 22.22+), with the backend above running:

```bash
cd frontend
npm ci
npm run dev                      # http://localhost:5173, forwards /api to :8080
PULSE_API=http://localhost:8081 npm run dev -- --port 5174   # if those ports are taken
```

Password-reset emails link to `APP_URL` (default `http://localhost:5173`). If the web app runs on another port,
start the backend with it, e.g. `APP_URL=http://localhost:5174 docker compose up --build`. Emails land in Mailpit.

Run the tests (Docker must be running; Testcontainers starts Postgres and Redis):

```bash
cd backend && ./mvnw verify                          # backend: tests + coverage gate
cd frontend && npm run lint && npm test && npm run build
```

## Tech stack

Java 21 · Spring Boot 4 · PostgreSQL 17 · Flyway · Redis 7 · React 19 · TypeScript · Vite · Tailwind · Recharts · Testcontainers · Vitest · JaCoCo · Docker · GitHub Actions
