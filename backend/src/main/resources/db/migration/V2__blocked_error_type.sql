-- New error type BLOCKED: a check refused by the SSRF guard (for example a
-- redirect to a private address). V1 is never edited; changes go in new files.

ALTER TABLE check_results DROP CONSTRAINT check_results_error_type_check;
ALTER TABLE check_results ADD CONSTRAINT check_results_error_type_check
    CHECK (error_type IN ('NONE', 'TIMEOUT', 'DNS', 'TLS', 'CONNECTION', 'STATUS', 'BLOCKED'));

ALTER TABLE incidents DROP CONSTRAINT incidents_first_error_type_check;
ALTER TABLE incidents ADD CONSTRAINT incidents_first_error_type_check
    CHECK (first_error_type IN ('TIMEOUT', 'DNS', 'TLS', 'CONNECTION', 'STATUS', 'BLOCKED'));
