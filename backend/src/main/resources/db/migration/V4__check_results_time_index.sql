-- The rollup and retention jobs select check results by time across all monitors.
CREATE INDEX check_results_checked_at_idx ON check_results (checked_at);
