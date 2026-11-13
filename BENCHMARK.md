# Benchmark

Can one Pulse server check 1,000 sites every minute, on time? Yes, on a laptop, with most of the
machine left idle. How the test works: [`loadtest/README.md`](loadtest/README.md) and `docs/DESIGN.md`,
"Load test method".

## Setup

| | |
|---|---|
| Machine | Apple M5 (10 cores), 16 GB, macOS 26.4 |
| Docker | 29.9.2, 10 CPUs and 7.7 GB given to Docker |
| Load | 1,000 monitors, every 60 s; 980 get 200, 20 get 503 (both after 50 ms) |
| Length | 30 min per run, first 2 min not counted (28 min measured) |
| Date | 2026-11-13 |

## Results

| | 1 instance | 2 instances |
|---|---|---|
| Checks due / done | 28,000 / 27,998 | 28,000 / 28,000 |
| Missed checks | 0.007 % (2) | 0 % |
| Double checks | **0** | **0** |
| Failed checks | 2.0 % (expected 2 %) | 2.0 % |
| Check delay p50 | 508 ms | 506 ms |
| Check delay p95 | 962 ms | 959 ms |
| Check delay p99 | 1,002 ms | 1,000 ms |
| Check delay max | 1,020 ms | 1,049 ms |
| Pulse CPU, average (peak) | 3.6 % (21.7 %) | 2.2 % + 2.3 % (29.7 %, 19.8 %) |
| Pulse memory | 483 MiB | 505 + 528 MiB |
| PostgreSQL CPU / memory | 2.9 % / 64 MiB | 2.9 % / 85 MiB |

CPU is `docker stats`: 100 % = one core. Check delay = when the check finished minus when it was due.
Raw numbers: [`loadtest/results/`](loadtest/results/).

## What the numbers mean

- **The delay is the scheduler's 1-second tick, not load.** The scheduler wakes once a second and checks everything
  that became due since. A check due at a random moment waits 0–1 s for the next tick, plus the 50 ms answer, so the
  delay spreads evenly from 0 to ~1 s: median ~0.5 s, p99 ~1 s. Both runs show exactly that shape, and the worst
  check was 1.05 s late out of 28,000.
- **Two instances share the work safely.** 0 double checks across 28,000: the database row lock
  (`FOR UPDATE SKIP LOCKED`) gives each due monitor to exactly one instance. Each instance used about half the CPU.
- **1,000 checks a minute is light.** One instance used about 4 % of one core on average. This test shows Pulse is
  far from its limit; it does not show where the limit is.
- **The 2 missed checks** (1-instance run) were most likely still in flight when measuring started: a check due in the
  last second of the window can finish up to 1 s later. The second run replaced that data, so this couldn't be
  confirmed. `run.py` now waits 5 s after the window before measuring.

## Limits of this test

- A laptop, not a server; the development stack and another project were running on it too.
- The target is a local fake that always answers in 50 ms. Real sites are slower and sometimes time out, which
  keeps checks in flight longer (each check runs on its own virtual thread, so waiting costs little CPU).
- Monitors were inserted into the database directly; the API and its rate limits were not part of the test.
- To be repeated on the production server (M9).
