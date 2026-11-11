-- AI summaries: a SKIPPED status (no API key configured) and a lease column, so the
-- summary worker never holds a row lock during the AI call.

ALTER TABLE incidents DROP CONSTRAINT incidents_summary_status_check;
ALTER TABLE incidents ADD CONSTRAINT incidents_summary_status_check
    CHECK (summary_status IN ('PENDING', 'DONE', 'FAILED', 'SKIPPED'));

ALTER TABLE incidents ADD COLUMN summary_next_attempt_at timestamptz NOT NULL DEFAULT now();

DROP INDEX incidents_summary_pending_idx;
CREATE INDEX incidents_summary_due_idx ON incidents (summary_next_attempt_at) WHERE summary_status = 'PENDING';
