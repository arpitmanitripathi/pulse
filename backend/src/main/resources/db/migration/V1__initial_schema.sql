-- Pulse v1 schema. Matches the "Data model" section of PLAN.md.
-- All times are timestamptz (stored in UTC).

CREATE TABLE users (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_email_lowercase CHECK (email = lower(email))
);
CREATE UNIQUE INDEX users_email_key ON users (email);

CREATE TABLE refresh_tokens (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TABLE monitors (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                  text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    url                   text        NOT NULL CHECK (char_length(url) <= 2048),
    method                text        NOT NULL DEFAULT 'GET' CHECK (method IN ('GET', 'HEAD')),
    interval_s            integer     NOT NULL CHECK (interval_s IN (30, 60, 120, 300)),
    timeout_ms            integer     NOT NULL DEFAULT 5000 CHECK (timeout_ms BETWEEN 1000 AND 10000),
    expected_status       integer     CHECK (expected_status BETWEEN 100 AND 599),
    active                boolean     NOT NULL DEFAULT true,
    state                 text        NOT NULL DEFAULT 'UNKNOWN' CHECK (state IN ('UNKNOWN', 'UP', 'DOWN')),
    consecutive_failures  integer     NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    consecutive_successes integer     NOT NULL DEFAULT 0 CHECK (consecutive_successes >= 0),
    next_check_at         timestamptz NOT NULL DEFAULT now(),
    last_checked_at       timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX monitors_user_idx ON monitors (user_id);
-- The scheduler's claim query reads this index.
CREATE INDEX monitors_due_idx ON monitors (next_check_at) WHERE active;

CREATE TABLE check_results (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitor_id    uuid        NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
    checked_at    timestamptz NOT NULL,
    success       boolean     NOT NULL,
    status_code   integer,
    latency_ms    integer     NOT NULL CHECK (latency_ms >= 0),
    error_type    text        NOT NULL CHECK (error_type IN ('NONE', 'TIMEOUT', 'DNS', 'TLS', 'CONNECTION', 'STATUS')),
    error_message text
);
CREATE INDEX check_results_monitor_time_idx ON check_results (monitor_id, checked_at);

CREATE TABLE incidents (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    monitor_id       uuid        NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
    opened_at        timestamptz NOT NULL,
    resolved_at      timestamptz,
    first_error_type text        NOT NULL CHECK (first_error_type IN ('TIMEOUT', 'DNS', 'TLS', 'CONNECTION', 'STATUS')),
    summary          text        CHECK (char_length(summary) <= 400),
    summary_status   text        NOT NULL DEFAULT 'PENDING' CHECK (summary_status IN ('PENDING', 'DONE', 'FAILED')),
    summary_attempts integer     NOT NULL DEFAULT 0
);
CREATE INDEX incidents_monitor_time_idx ON incidents (monitor_id, opened_at);
-- A monitor can have at most one open incident.
CREATE UNIQUE INDEX incidents_one_open_per_monitor ON incidents (monitor_id) WHERE resolved_at IS NULL;
CREATE INDEX incidents_summary_pending_idx ON incidents (opened_at) WHERE summary_status = 'PENDING';

CREATE TABLE alert_channels (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       text        NOT NULL CHECK (type IN ('EMAIL', 'SLACK', 'DISCORD')),
    target     text        NOT NULL CHECK (char_length(target) <= 2048),
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX alert_channels_user_idx ON alert_channels (user_id);

CREATE TABLE alert_outbox (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id     uuid        NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    channel_id      uuid        NOT NULL REFERENCES alert_channels (id) ON DELETE CASCADE,
    kind            text        NOT NULL CHECK (kind IN ('DOWN', 'RECOVERED')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- Exactly one DOWN and one RECOVERED alert per incident per channel.
    CONSTRAINT alert_outbox_once UNIQUE (incident_id, channel_id, kind)
);
CREATE INDEX alert_outbox_due_idx ON alert_outbox (next_attempt_at) WHERE sent_at IS NULL;

CREATE TABLE hourly_rollups (
    monitor_id  uuid        NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
    hour        timestamptz NOT NULL,
    checks      integer     NOT NULL,
    failures    integer     NOT NULL,
    latency_p50 integer,
    latency_p95 integer,
    PRIMARY KEY (monitor_id, hour)
);

CREATE TABLE status_pages (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    slug       text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$'),
    title      text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX status_pages_user_idx ON status_pages (user_id);

CREATE TABLE status_page_monitors (
    status_page_id uuid    NOT NULL REFERENCES status_pages (id) ON DELETE CASCADE,
    monitor_id     uuid    NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
    position       integer NOT NULL DEFAULT 0,
    PRIMARY KEY (status_page_id, monitor_id)
);
