package dev.pulse.alert;

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
 * Delivers queued alerts from alert_outbox.
 *
 * <ol>
 * <li><b>Claim</b> (one statement): pick due unsent rows with FOR UPDATE SKIP LOCKED,
 * add 1 to attempts and set next_attempt_at 2 minutes ahead (a lease). Commit.</li>
 * <li><b>Send</b> each on a virtual thread, with no transaction open.</li>
 * <li><b>Record</b>: sent_at on success; otherwise the next retry time and the error.</li>
 * </ol>
 * If this instance dies mid-send, the lease runs out and another run retries it.
 * A RECOVERED row is only claimed once its channel's DOWN row was sent or given up.
 */
@Component
public class AlertOutboxDispatcher implements AutoCloseable {

	public static final int MAX_ATTEMPTS = 6;
	static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
	static final Duration LEASE = Duration.ofMinutes(2);
	static final int BATCH = 50;

	private static final Logger log = LoggerFactory.getLogger(AlertOutboxDispatcher.class);

	private static final String CLAIM_SQL = """
			WITH due AS (
			    SELECT o.id
			    FROM alert_outbox o
			    WHERE o.sent_at IS NULL AND o.attempts < :max AND o.next_attempt_at <= :now
			      AND (o.kind = 'DOWN' OR NOT EXISTS (
			            SELECT 1 FROM alert_outbox d
			            WHERE d.incident_id = o.incident_id AND d.channel_id = o.channel_id
			              AND d.kind = 'DOWN' AND d.sent_at IS NULL AND d.attempts < :max))
			    ORDER BY o.next_attempt_at
			    LIMIT :limit
			    FOR UPDATE OF o SKIP LOCKED
			)
			UPDATE alert_outbox o
			SET attempts = o.attempts + 1, next_attempt_at = :leaseUntil
			FROM due, alert_channels c, incidents i, monitors m
			WHERE o.id = due.id AND c.id = o.channel_id AND i.id = o.incident_id AND m.id = i.monitor_id
			RETURNING o.id, o.kind, o.attempts, c.type, c.target, c.name AS channel_name,
			          i.opened_at, i.resolved_at, i.first_error_type, m.name AS monitor_name, m.url,
			          (SELECT cr.error_message FROM check_results cr
			           WHERE cr.monitor_id = m.id AND NOT cr.success AND cr.checked_at <= i.opened_at
			           ORDER BY cr.checked_at DESC LIMIT 1) AS reason
			""";

	private final NamedParameterJdbcTemplate jdbc;
	private final AlertSenders senders;
	private final Clock clock;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public AlertOutboxDispatcher(NamedParameterJdbcTemplate jdbc, AlertSenders senders, Clock clock) {
		this.jdbc = jdbc;
		this.senders = senders;
		this.clock = clock;
	}

	/** Claims and sends due alerts; returns how many were attempted. */
	public int dispatchDue() throws InterruptedException {
		Instant now = clock.instant();
		List<Claimed> claimed = jdbc.query(CLAIM_SQL, new MapSqlParameterSource()
				.addValue("max", MAX_ATTEMPTS)
				.addValue("now", utc(now))
				.addValue("leaseUntil", utc(now.plus(LEASE)))
				.addValue("limit", BATCH), (rs, i) -> {
					String reason = rs.getString("reason");
					AlertMessage message = new AlertMessage(AlertKind.valueOf(rs.getString("kind")),
							rs.getString("monitor_name"), rs.getString("url"),
							reason != null ? reason : rs.getString("first_error_type"),
							rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
							toInstant(rs.getObject("resolved_at", OffsetDateTime.class)),
							rs.getString("channel_name"));
					return new Claimed(rs.getLong("id"), rs.getInt("attempts"),
							AlertChannelType.valueOf(rs.getString("type")), rs.getString("target"), message);
				});
		List<Callable<Void>> sends = new ArrayList<>();
		for (Claimed alert : claimed) {
			sends.add(() -> {
				deliver(alert);
				return null;
			});
		}
		executor.invokeAll(sends);
		return claimed.size();
	}

	private void deliver(Claimed alert) {
		try {
			senders.send(alert.type(), alert.target(), alert.message());
			jdbc.update("UPDATE alert_outbox SET sent_at = :now, last_error = NULL WHERE id = :id",
					new MapSqlParameterSource("now", utc(clock.instant())).addValue("id", alert.id()));
			log.info("alert.sent outbox_id={} kind={} type={}", alert.id(), alert.message().kind(), alert.type());
		}
		catch (AlertDeliveryException | RuntimeException ex) {
			Instant retryAt = clock.instant().plus(backoff(alert.attempts()));
			jdbc.update("UPDATE alert_outbox SET next_attempt_at = :retry, last_error = :error WHERE id = :id",
					new MapSqlParameterSource("retry", utc(retryAt))
						.addValue("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())
						.addValue("id", alert.id()));
			if (alert.attempts() >= MAX_ATTEMPTS) {
				log.warn("alert.gave_up outbox_id={} type={} attempts={}", alert.id(), alert.type(), alert.attempts());
			}
			else {
				log.info("alert.failed outbox_id={} type={} attempt={}", alert.id(), alert.type(), alert.attempts());
			}
		}
	}

	/** Wait after attempt n: 30 s, 1 min, 2 min, 4 min, 8 min. */
	static Duration backoff(int attempt) {
		return FIRST_BACKOFF.multipliedBy(1L << Math.max(0, attempt - 1));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	@Override
	@PreDestroy
	public void close() {
		executor.close();
	}

	private record Claimed(long id, int attempts, AlertChannelType type, String target, AlertMessage message) {
	}

	/** For tests and the incident API: one outbox row's delivery state. */
	public record Delivery(UUID channelId, String channelName, AlertChannelType type, AlertKind kind, int attempts,
			Instant sentAt, String lastError) {
	}

}
