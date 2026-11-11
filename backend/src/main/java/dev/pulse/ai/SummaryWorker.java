package dev.pulse.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Fills in incidents.summary.
 *
 * <ul>
 * <li>No API key: every PENDING incident becomes SKIPPED. Nothing is sent anywhere.</li>
 * <li>Otherwise: claim up to 10 PENDING incidents (SKIP LOCKED, 1-minute lease), commit,
 * call the AI with no transaction open, then store DONE, or retry once after 30 s, then FAILED.</li>
 * </ul>
 * Alerts never wait for this: they were queued when the incident opened.
 */
@Component
public class SummaryWorker implements AutoCloseable {

	public static final int MAX_ATTEMPTS = 2; // the first try and one retry
	static final int BATCH = 10;
	static final int CHECKS = 20;
	static final Duration LEASE = Duration.ofMinutes(1);
	static final Duration RETRY_AFTER = Duration.ofSeconds(30);

	private static final Logger log = LoggerFactory.getLogger(SummaryWorker.class);

	private static final String CLAIM_SQL = """
			WITH due AS (
			    SELECT id FROM incidents
			    WHERE summary_status = 'PENDING' AND summary_next_attempt_at <= :now
			    ORDER BY opened_at
			    LIMIT :limit
			    FOR UPDATE SKIP LOCKED
			)
			UPDATE incidents i
			SET summary_attempts = i.summary_attempts + 1, summary_next_attempt_at = :leaseUntil
			FROM due, monitors m
			WHERE i.id = due.id AND m.id = i.monitor_id
			RETURNING i.id, i.monitor_id, i.opened_at, i.summary_attempts, m.name, m.url
			""";

	private final NamedParameterJdbcTemplate jdbc;
	private final IncidentSummarizer summarizer;
	private final Clock clock;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public SummaryWorker(NamedParameterJdbcTemplate jdbc, IncidentSummarizer summarizer, Clock clock) {
		this.jdbc = jdbc;
		this.summarizer = summarizer;
		this.clock = clock;
	}

	/** Returns how many incidents were handled (summarised, failed, retried or skipped). */
	public int runOnce() throws InterruptedException {
		if (!summarizer.enabled()) {
			return jdbc.update("UPDATE incidents SET summary_status = 'SKIPPED' WHERE summary_status = 'PENDING'",
					new MapSqlParameterSource());
		}
		Instant now = clock.instant();
		List<Claimed> claimed = jdbc.query(CLAIM_SQL, new MapSqlParameterSource()
				.addValue("now", utc(now))
				.addValue("leaseUntil", utc(now.plus(LEASE)))
				.addValue("limit", BATCH), (rs, i) -> new Claimed(
						rs.getObject("id", UUID.class), rs.getObject("monitor_id", UUID.class),
						rs.getObject("opened_at", OffsetDateTime.class).toInstant(), rs.getInt("summary_attempts"),
						rs.getString("name"), rs.getString("url")));
		List<Callable<Void>> calls = new ArrayList<>();
		for (Claimed incident : claimed) {
			calls.add(() -> {
				summarise(incident);
				return null;
			});
		}
		executor.invokeAll(calls);
		return claimed.size();
	}

	private void summarise(Claimed incident) {
		try {
			String summary = summarizer.summarize(new SummaryInput(incident.monitorName(), incident.url(),
					incident.openedAt(), recentChecks(incident.monitorId())));
			jdbc.update("""
					UPDATE incidents SET summary = :summary, summary_status = 'DONE'
					WHERE id = :id AND summary_status = 'PENDING'""",
					new MapSqlParameterSource("summary", summary).addValue("id", incident.id()));
			log.info("summary.done incident_id={} attempt={}", incident.id(), incident.attempts());
		}
		catch (SummaryException | RuntimeException ex) {
			boolean giveUp = incident.attempts() >= MAX_ATTEMPTS;
			jdbc.update(giveUp
					? "UPDATE incidents SET summary_status = 'FAILED' WHERE id = :id AND summary_status = 'PENDING'"
					: "UPDATE incidents SET summary_next_attempt_at = :retry WHERE id = :id AND summary_status = 'PENDING'",
					new MapSqlParameterSource("id", incident.id())
						.addValue("retry", utc(clock.instant().plus(RETRY_AFTER))));
			log.warn("summary.{} incident_id={} attempt={} error={}", giveUp ? "failed" : "retry", incident.id(),
					incident.attempts(), ex.getMessage());
		}
	}

	private List<SummaryInput.CheckLine> recentChecks(UUID monitorId) {
		return jdbc.query("""
				SELECT checked_at, status_code, latency_ms, error_type, error_message
				FROM check_results WHERE monitor_id = :monitor
				ORDER BY checked_at DESC, id DESC LIMIT :limit
				""", new MapSqlParameterSource("monitor", monitorId).addValue("limit", CHECKS),
				(rs, i) -> new SummaryInput.CheckLine(rs.getObject("checked_at", OffsetDateTime.class).toInstant(),
						(Integer) rs.getObject("status_code"), rs.getInt("latency_ms"), rs.getString("error_type"),
						rs.getString("error_message")));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	@Override
	@PreDestroy
	public void close() {
		executor.close();
	}

	private record Claimed(UUID id, UUID monitorId, Instant openedAt, int attempts, String monitorName, String url) {
	}

}
