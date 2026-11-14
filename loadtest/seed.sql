-- 1,000 monitors at 60 s for 50 users (the per-user limit is 20), inserted straight into the
-- database. Every 50th monitor (20 = 2 %) points at /fail. First checks are spread evenly over
-- one minute, like monitors that were created at different times.
--
-- Safety: this file empties the users table. It refuses to run unless the server was started with
-- `-c pulse.environment=loadtest` (only loadtest/docker-compose.yml does that). run.py sends the whole file as
-- one psql -c string, which PostgreSQL runs as one transaction: the error below stops every statement after it.
DO $$
BEGIN
  IF current_setting('pulse.environment', true) IS DISTINCT FROM 'loadtest' THEN
    RAISE EXCEPTION 'seed.sql only runs on the load-test database (server setting pulse.environment=loadtest)';
  END IF;
END
$$;

TRUNCATE users CASCADE;

INSERT INTO users (email, password_hash)
SELECT 'load' || g || '@loadtest.local', '$2a$10$abcdefghijklmnopqrstuuJ8m4i3o6Sn3m0VZ6dTt0W3JbQ5dPmZy'
FROM generate_series(1, 50) g;

INSERT INTO monitors (user_id, name, url, interval_s, next_check_at)
SELECT u.id,
       'Load ' || n,
       CASE WHEN n % 50 = 0 THEN 'http://wiremock:8080/fail' ELSE 'http://wiremock:8080/ok' END,
       60,
       now() + make_interval(secs => n * 60.0 / 1000)
FROM generate_series(1, 1000) n
JOIN users u ON u.email = 'load' || ((n - 1) / 20 + 1) || '@loadtest.local';

SELECT count(*) AS monitors, count(*) FILTER (WHERE url LIKE '%/fail') AS failing FROM monitors;
