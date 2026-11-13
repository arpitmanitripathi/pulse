-- When each check was due. Check delay (checked_at - scheduled_at) is the scheduler's key number,
-- and two results with the same due time for one monitor would expose a double check.
-- Null for results recorded before this migration.
ALTER TABLE check_results ADD COLUMN scheduled_at timestamptz;
