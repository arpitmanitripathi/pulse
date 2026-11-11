package dev.pulse.check;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.pulse.incident.IncidentStateMachine;
import dev.pulse.incident.IncidentStateMachine.Transition;
import dev.pulse.monitor.MonitorState;

/**
 * Stores a check result and applies the incident rules, all in one
 * transaction with the monitor row locked:
 * <ol>
 * <li>lock the monitor (results for the same monitor are applied one at a time)</li>
 * <li>store the result</li>
 * <li>move the state machine</li>
 * <li>on DOWN: open an incident and queue a DOWN alert per channel</li>
 * <li>on recovery: resolve it and queue RECOVERED for the channels that got DOWN</li>
 * </ol>
 * Because the incident and its alert rows commit together, an incident can
 * never exist without its alerts, and a crash can never leave half of it.
 */
@Component
public class CheckResultProcessor {

	private static final Logger log = LoggerFactory.getLogger(CheckResultProcessor.class);

	private final JdbcTemplate jdbc;

	public CheckResultProcessor(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public void record(ClaimedMonitor monitor, CheckOutcome outcome) {
		List<Locked> rows = jdbc.query("""
				SELECT url, state, consecutive_failures, consecutive_successes
				FROM monitors WHERE id = ? FOR UPDATE
				""", (rs, i) -> new Locked(rs.getString("url"), MonitorState.valueOf(rs.getString("state")),
				rs.getInt("consecutive_failures"), rs.getInt("consecutive_successes")), monitor.id());
		if (rows.isEmpty()) {
			return; // deleted while the check ran
		}
		Locked current = rows.get(0);
		if (!current.url().equals(monitor.url())) {
			return; // the URL changed while the check ran: this result is about the old target
		}

		OffsetDateTime at = utc(outcome.checkedAt());
		jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, status_code, latency_ms, error_type, error_message)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", monitor.id(), at, outcome.success(), outcome.statusCode(), outcome.latencyMs(),
				outcome.errorType().name(), outcome.errorMessage());

		Transition next = IncidentStateMachine.next(current.state(), current.failures(), current.successes(),
				outcome.success());
		jdbc.update("""
				UPDATE monitors SET state = ?, consecutive_failures = ?, consecutive_successes = ?, last_checked_at = ?
				WHERE id = ?
				""", next.state().name(), next.failures(), next.successes(), at, monitor.id());

		switch (next.event()) {
			case OPEN_INCIDENT -> openIncident(monitor.id(), outcome, at);
			case RESOLVE_INCIDENT -> resolveIncident(monitor.id(), at);
			case NONE -> {
			}
		}
	}

	private void openIncident(UUID monitorId, CheckOutcome outcome, OffsetDateTime at) {
		UUID incidentId = jdbc.queryForObject("""
				INSERT INTO incidents (monitor_id, opened_at, first_error_type, summary_next_attempt_at)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, monitorId, at, outcome.errorType().name(), at); // summary due now, on the app's clock
		int alerts = jdbc.update("""
				INSERT INTO alert_outbox (incident_id, channel_id, kind, next_attempt_at)
				SELECT ?, c.id, 'DOWN', ?
				FROM alert_channels c JOIN monitors m ON m.user_id = c.user_id
				WHERE m.id = ?
				""", incidentId, at, monitorId);
		log.info("incident.opened incident_id={} monitor_id={} error_type={} alerts_queued={}", incidentId,
				monitorId, outcome.errorType(), alerts);
	}

	private void resolveIncident(UUID monitorId, OffsetDateTime at) {
		List<UUID> resolved = jdbc.queryForList("""
				UPDATE incidents SET resolved_at = ? WHERE monitor_id = ? AND resolved_at IS NULL RETURNING id
				""", UUID.class, at, monitorId);
		for (UUID incidentId : resolved) {
			// Only channels that were told "down" are told "recovered".
			int alerts = jdbc.update("""
					INSERT INTO alert_outbox (incident_id, channel_id, kind, next_attempt_at)
					SELECT incident_id, channel_id, 'RECOVERED', ?
					FROM alert_outbox WHERE incident_id = ? AND kind = 'DOWN'
					""", at, incidentId);
			log.info("incident.resolved incident_id={} monitor_id={} alerts_queued={}", incidentId, monitorId,
					alerts);
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private record Locked(String url, MonitorState state, int failures, int successes) {
	}

}
