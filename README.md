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
| M5 AI summary | ⏳ |
| M6 Stats + status page | ⏳ |
| M7 Frontend | ⏳ |
| M8 Load test | ⏳ |
| M9 Ship | ⏳ |

## Run it locally

Requirements: Docker, Java 21.

```bash
docker compose up --build        # app on :8080, Postgres :5433, Redis :6380, Mailpit inbox :8025
curl localhost:8080/actuator/health
```

API docs and a try-it-out console: http://localhost:8080/swagger-ui
(register, log in, then click **Authorize** and paste the access token).

Run the tests (Docker must be running; Testcontainers starts Postgres and Redis):

```bash
cd backend
./mvnw verify
```

## Tech stack

Java 21 · Spring Boot 4 · PostgreSQL 17 · Flyway · Redis 7 · Testcontainers · JaCoCo · Docker · GitHub Actions
