-- 1,000 monitors at 60 s for 50 users (the per-user limit is 20), inserted straight into the
-- database. Every 50th monitor (20 = 2 %) points at /fail. First checks are spread evenly over
-- one minute, like monitors that were created at different times.
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
