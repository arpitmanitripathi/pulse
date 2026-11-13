# Load test

How many sites one Pulse server can check on time. Method and reasons: `docs/DESIGN.md`,
"Load test method". Results: [`../BENCHMARK.md`](../BENCHMARK.md).

```bash
# from the repository root; Docker running, Mac plugged in
caffeinate -dimsu python3 loadtest/run.py --instances 1 --minutes 30   # one Pulse instance
caffeinate -dimsu python3 loadtest/run.py --instances 2 --minutes 30   # two, sharing one database
```

`caffeinate` keeps macOS awake: a sleeping laptop pauses Docker and every check with it.

What `run.py` does:

1. Starts its own Compose project, `pulse-loadtest` (Pulse, PostgreSQL, Redis, WireMock), with its own
   database, so development data is never touched.
2. Seeds 1,000 monitors at 60 s straight into the database (`seed.sql`): 980 point at `/ok`
   (200 after 50 ms), 20 at `/fail` (503 after 50 ms); first checks spread over one minute.
3. Records `docker stats` every 10 s while it waits.
4. Measures from `check_results`, ignoring the first 2 minutes:
   - **check delay**: `checked_at − scheduled_at` (p50 / p95 / p99 / max)
   - **missed checks**: 1 − done ÷ due
   - **double checks**: monitor + due time pairs with more than one result (must be 0)
5. Writes `results/<time>-<n>-instance.json` and stops the Pulse containers.

The SSRF guard trusts exactly one host here, `wiremock` (`PULSE_SSRF_TRUSTED_HOSTS`), because the target is a
private Docker address. That setting exists only in this stack.

Clean up afterwards: `docker compose -p pulse-loadtest -f loadtest/docker-compose.yml down`.
