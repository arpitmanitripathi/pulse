package dev.pulse.check;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a check result. M4 adds the incident state machine here, in the same
 * transaction.
 */
@Component
public class CheckResultProcessor {

	private final JdbcTemplate jdbc;

	public CheckResultProcessor(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** If the monitor was deleted while its check was running, the result is dropped. */
	@Transactional
	public void record(ClaimedMonitor monitor, CheckOutcome outcome) {
		int inserted = jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, status_code, latency_ms, error_type, error_message)
				SELECT ?, ?, ?, ?, ?, ?, ?
				WHERE EXISTS (SELECT 1 FROM monitors WHERE id = ?)
				""",
				monitor.id(), utc(outcome.checkedAt()), outcome.success(), outcome.statusCode(),
				outcome.latencyMs(), outcome.errorType().name(), outcome.errorMessage(), monitor.id());
		if (inserted == 1) {
			jdbc.update("UPDATE monitors SET last_checked_at = ? WHERE id = ?", utc(outcome.checkedAt()),
					monitor.id());
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
