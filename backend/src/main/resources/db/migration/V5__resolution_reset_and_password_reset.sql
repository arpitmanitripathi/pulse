-- 1. Why an incident closed: it recovered (2 good checks), or the monitor was paused, resumed or
--    pointed at a new URL. Set together with resolved_at.
ALTER TABLE incidents ADD COLUMN resolution text
    CHECK (resolution IN ('RECOVERED', 'MONITOR_CHANGED'));
-- Existing rows: a recovery sets resolved_at to the exact time of the successful check that
-- caused it; closing by a change uses the time of the change, which matches no check.
UPDATE incidents i SET resolution = CASE
    WHEN EXISTS (SELECT 1 FROM check_results c
                 WHERE c.monitor_id = i.monitor_id AND c.checked_at = i.resolved_at AND c.success)
    THEN 'RECOVERED' ELSE 'MONITOR_CHANGED' END
WHERE resolved_at IS NOT NULL;
ALTER TABLE incidents ADD CONSTRAINT incidents_resolution_with_resolved_at
    CHECK ((resolved_at IS NULL) = (resolution IS NULL));

-- 2. When the monitor last started fresh (created, URL changed or resumed). The AI summary
--    only reads checks from after this moment.
ALTER TABLE monitors ADD COLUMN reset_at timestamptz NOT NULL DEFAULT now();
UPDATE monitors SET reset_at = created_at;

-- 3. Password reset links. Only a SHA-256 hash of the token is stored.
CREATE TABLE password_reset_tokens (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_tokens_user_idx ON password_reset_tokens (user_id);
