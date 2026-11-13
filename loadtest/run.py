#!/usr/bin/env python3
"""Runs one load test (docs/DESIGN.md, "Load test method") and writes its results.

    caffeinate -dimsu python3 loadtest/run.py --instances 1 --minutes 30

Starts the separate pulse-loadtest Compose project (its own database), seeds 1,000 monitors,
samples `docker stats` every 10 s, waits, then measures from the database:
  check delay      checked_at - scheduled_at (p50 / p95 / p99 / max)
  missed checks    1 - checks done / checks due
  double checks    (monitor, scheduled_at) pairs with more than one result: must be 0
The first --warmup minutes are not counted. Results: loadtest/results/<time>-<n>-instance.json
No dependencies beyond Python 3 and Docker.
"""
import argparse
import json
import re
import subprocess
import sys
import threading
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
COMPOSE = ['docker', 'compose', '-p', 'pulse-loadtest', '-f', str(HERE / 'docker-compose.yml')]
MONITORS = 1000


def sh(args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kw).stdout


def psql(sql):
    return sh(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-U', 'pulse', '-d', 'pulse', '-At', '-F', '|', '-c', sql]).strip()


def wait_healthy(port, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f'http://localhost:{port}/actuator/health', timeout=2) as r:
                if r.status == 200:
                    return
        except Exception:
            pass
        time.sleep(2)
    sys.exit(f'app on :{port} did not become healthy')


def to_mib(text):
    number, unit = re.match(r'([\d.]+)\s*([A-Za-z]+)', text).groups()
    return float(number) * {'B': 1 / 1048576, 'KiB': 1 / 1024, 'kB': 1 / 1024, 'MiB': 1, 'MB': 1, 'GiB': 1024, 'GB': 1024}[unit]


def sample_stats(stop, samples):
    """docker stats every 10 s: CPU % (100 = one core) and memory per container."""
    while not stop.is_set():
        out = sh(['docker', 'stats', '--no-stream', '--format', '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}'])
        for line in out.splitlines():
            name, cpu, mem = line.split('|')
            if name.startswith('pulse-loadtest-'):
                service = name.removeprefix('pulse-loadtest-').rsplit('-', 1)[0]
                samples.setdefault(service, []).append((float(cpu.rstrip('%')), to_mib(mem.split('/')[0].strip())))
        stop.wait(10)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--instances', type=int, choices=[1, 2], default=1)
    ap.add_argument('--minutes', type=float, default=30)
    ap.add_argument('--warmup', type=float, default=2)
    args = ap.parse_args()

    profile = ['--profile', 'two'] if args.instances == 2 else []
    print(f'starting stack with {args.instances} instance(s)…', flush=True)
    sh(COMPOSE + ['stop', 'app', 'app2'])                       # a clean start for the app(s)
    sh(COMPOSE + profile + ['up', '-d', '--build', '--wait', 'postgres', 'redis', 'wiremock'])
    sh(COMPOSE + profile + ['up', '-d', '--build'])
    wait_healthy(18080)
    if args.instances == 2:
        wait_healthy(18081)
    print(psql((HERE / 'seed.sql').read_text()).splitlines()[-1], '(monitors|failing)', flush=True)
    started = psql('SELECT now()')

    stop, samples = threading.Event(), {}
    sampler = threading.Thread(target=sample_stats, args=(stop, samples), daemon=True)
    sampler.start()
    total = args.minutes * 60
    t0 = time.time()
    while (left := total - (time.time() - t0)) > 0:
        print(f'  {int((time.time() - t0) / 60)} / {args.minutes:g} min, '
              f'checks so far: {psql("SELECT count(*) FROM check_results")}', flush=True)
        time.sleep(min(60, left))
    time.sleep(5)                                          # let checks due in the last second finish
    stop.set()
    sampler.join()

    window = (f"scheduled_at >= timestamptz '{started}' + interval '{args.warmup} minutes' "
              f"AND scheduled_at < timestamptz '{started}' + interval '{args.minutes} minutes'")
    measured_min = args.minutes - args.warmup
    p50, p95, p99, worst, count, failed = psql(f"""
        SELECT round(percentile_cont(0.50) WITHIN GROUP (ORDER BY d)), round(percentile_cont(0.95) WITHIN GROUP (ORDER BY d)),
               round(percentile_cont(0.99) WITHIN GROUP (ORDER BY d)), round(max(d)), count(*), count(*) FILTER (WHERE NOT success)
        FROM (SELECT extract(epoch FROM checked_at - scheduled_at) * 1000 AS d, success FROM check_results WHERE {window}) x
    """).split('|')
    doubles = int(psql(f'SELECT count(*) FROM (SELECT 1 FROM check_results WHERE {window} '
                       'GROUP BY monitor_id, scheduled_at HAVING count(*) > 1) x'))
    due = MONITORS * measured_min                      # every monitor is due once a minute
    count = int(count)
    result = {
        'run_at_utc': datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M'),
        'instances': args.instances, 'monitors': MONITORS, 'interval_s': 60,
        'minutes_total': args.minutes, 'minutes_measured': measured_min,
        'checks_due': round(due), 'checks_done': count, 'checks_per_minute': round(count / measured_min),
        'missed_pct': round(max(0.0, 1 - count / due) * 100, 3),
        'failed_pct': round(int(failed) / count * 100, 2) if count else None,
        'double_checks': doubles,
        'delay_ms': {'p50': int(float(p50)), 'p95': int(float(p95)), 'p99': int(float(p99)), 'max': int(float(worst))},
        'resources': {svc: {'cpu_pct_avg': round(sum(c for c, _ in v) / len(v), 1), 'cpu_pct_max': round(max(c for c, _ in v), 1),
                            'mem_mib_avg': round(sum(m for _, m in v) / len(v)), 'mem_mib_max': round(max(m for _, m in v))}
                      for svc, v in sorted(samples.items())},
    }
    out = HERE / 'results' / f"{datetime.now().strftime('%Y%m%d-%H%M')}-{args.instances}-instance.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    print('saved', out.relative_to(HERE.parent))
    sh(COMPOSE + ['stop', 'app', 'app2'])                       # stop checking; data stays for inspection


if __name__ == '__main__':
    main()
