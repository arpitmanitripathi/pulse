# Load test

How many sites one Pulse server can check on time. Method: `docs/DESIGN.md`, "Load test method".
Results: [`../BENCHMARK.md`](../BENCHMARK.md).

```bash
# from the repository root, with Docker running
python3 loadtest/run.py --instances 1 --minutes 30   # one Pulse instance
python3 loadtest/run.py --instances 2 --minutes 30   # two instances sharing one database
```

`run.py` starts its own Compose project (`pulse-loadtest`) with its own database, seeds 1,000 monitors
(`seed.sql`), waits, measures check delay, missed checks and double checks from the database, and writes
`results/<time>-<n>-instance.json`. Each run takes the given minutes; keep the machine awake.

Clean up: `docker compose -p pulse-loadtest -f loadtest/docker-compose.yml down`.
