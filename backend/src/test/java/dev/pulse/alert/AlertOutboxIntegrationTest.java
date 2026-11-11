package dev.pulse.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import dev.pulse.IntegrationTest;
import dev.pulse.support.MutableClock;
import dev.pulse.support.RecordingAlertSenders;
import dev.pulse.support.TestUsers;

@IntegrationTest
class AlertOutboxIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	NamedParameterJdbcTemplate named;

	@Autowired
	TestUsers testUsers;

	@Autowired
	RecordingAlertSenders senders;

	final Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	final MutableClock clock = new MutableClock(start);
	AlertOutboxDispatcher dispatcher;
	UUID incidentId;
	UUID slack;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		senders.reset();
		dispatcher = new AlertOutboxDispatcher(named, senders, clock);
		UUID userId = testUsers.create("alice@example.com").id();
		UUID monitorId = jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s, state) VALUES (?, 'API', 'https://example.com/', 60, 'DOWN')
				RETURNING id""", UUID.class, userId);
		jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, status_code, latency_ms, error_type, error_message)
				VALUES (?, ?, false, 503, 40, 'STATUS', 'Expected a 2xx status, got 503.')""", monitorId,
				utc(start.minusSeconds(5)));
		incidentId = jdbc.queryForObject("""
				INSERT INTO incidents (monitor_id, opened_at, first_error_type) VALUES (?, ?, 'STATUS') RETURNING id""",
				UUID.class, monitorId, utc(start));
		slack = jdbc.queryForObject("""
				INSERT INTO alert_channels (user_id, type, target, name)
				VALUES (?, 'SLACK', 'https://hooks.slack.com/services/T/B/x', 'Team Slack') RETURNING id""", UUID.class,
				userId);
	}

	@AfterEach
	void tearDown() {
		dispatcher.close();
	}

	@Test
	void aDueAlertIsSentOnceWithTheReason() throws Exception {
		queue("DOWN", start);

		assertThat(dispatcher.dispatchDue()).isEqualTo(1);
		assertThat(dispatcher.dispatchDue()).isZero(); // already sent

		assertThat(senders.sent()).hasSize(1);
		AlertMessage message = senders.sent().get(0).message();
		assertThat(message.kind()).isEqualTo(AlertKind.DOWN);
		assertThat(message.text()).contains("API is down", "Reason: Expected a 2xx status, got 503.");
		assertThat(row()).containsEntry("attempts", 1).containsEntry("last_error", null);
		assertThat(row().get("sent_at")).isNotNull();
	}

	@Test
	void failuresAreRetriedWithGrowingWaitsThenGivenUp() throws Exception {
		queue("DOWN", start);
		senders.failNext(100);

		List<Duration> waits = new ArrayList<>();
		for (int attempt = 1; attempt <= AlertOutboxDispatcher.MAX_ATTEMPTS; attempt++) {
			assertThat(dispatcher.dispatchDue()).as("attempt %d", attempt).isEqualTo(1);
			Instant next = ((java.sql.Timestamp) row().get("next_attempt_at")).toInstant();
			waits.add(Duration.between(clock.instant(), next));
			assertThat(dispatcher.dispatchDue()).as("not due again yet").isZero();
			clock.advance(Duration.between(clock.instant(), next));
		}

		assertThat(waits.subList(0, 5)).containsExactly(Duration.ofSeconds(30), Duration.ofMinutes(1),
				Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8));
		clock.advance(Duration.ofHours(1));
		assertThat(dispatcher.dispatchDue()).as("given up after 6 attempts").isZero();
		assertThat(row()).containsEntry("attempts", 6).containsEntry("last_error", "Slack answered HTTP 500.");
		assertThat(row().get("sent_at")).isNull();
	}

	@Test
	void recoveredWaitsUntilDownWasDelivered() throws Exception {
		queue("DOWN", start);
		queue("RECOVERED", start);
		senders.failNext(1);

		dispatcher.dispatchDue(); // DOWN fails; RECOVERED must not overtake it
		assertThat(senders.sent()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT attempts FROM alert_outbox WHERE kind = 'RECOVERED'", Integer.class))
			.isZero();

		clock.advance(Duration.ofSeconds(30));
		dispatcher.dispatchDue(); // DOWN goes out
		dispatcher.dispatchDue(); // now RECOVERED may follow

		assertThat(senders.sent()).extracting(sent -> sent.message().kind())
			.containsExactly(AlertKind.DOWN, AlertKind.RECOVERED);
	}

	@Test
	void recoveredStillGoesOutIfDownWasGivenUp() throws Exception {
		queue("RECOVERED", start);
		queue("DOWN", start);
		jdbc.update("UPDATE alert_outbox SET attempts = 6, last_error = 'gone' WHERE kind = 'DOWN'");

		dispatcher.dispatchDue();

		assertThat(senders.sent()).extracting(sent -> sent.message().kind()).containsExactly(AlertKind.RECOVERED);
	}

	@Test
	void aSenderThatDiesMidSendIsRetriedAfterTheLease() throws Exception {
		queue("DOWN", start);
		// Simulate a crash: the row was claimed (attempts 1, leased 2 min) but never marked.
		jdbc.update("UPDATE alert_outbox SET attempts = 1, next_attempt_at = ?", utc(start.plusSeconds(120)));

		assertThat(dispatcher.dispatchDue()).isZero();
		clock.advance(Duration.ofSeconds(121));
		assertThat(dispatcher.dispatchDue()).isEqualTo(1);
		assertThat(senders.sent()).hasSize(1);
	}

	@Test
	void twoDispatchersNeverSendTheSameAlert() throws Exception {
		for (int i = 0; i < 40; i++) {
			UUID channel = jdbc.queryForObject("""
					INSERT INTO alert_channels (user_id, type, target, name)
					SELECT user_id, 'EMAIL', 'ops' || ? || '@example.com', 'c' || ? FROM alert_channels LIMIT 1
					RETURNING id""", UUID.class, i, i);
			jdbc.update("INSERT INTO alert_outbox (incident_id, channel_id, kind, next_attempt_at) VALUES (?, ?, 'DOWN', ?)",
					incidentId, channel, utc(start));
		}
		AlertOutboxDispatcher other = new AlertOutboxDispatcher(named, senders, clock);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Callable<Integer>> runs = List.of(dispatcher::dispatchDue, other::dispatchDue);
		int attempted = 0;
		for (Future<Integer> f : pool.invokeAll(runs)) {
			attempted += f.get();
		}
		pool.shutdown();
		other.close();

		assertThat(attempted).isEqualTo(40);
		assertThat(senders.sent()).hasSize(40);
		assertThat(senders.sent()).extracting(RecordingAlertSenders.Sent::target).doesNotHaveDuplicates();
	}

	// ---------- helpers ----------

	private void queue(String kind, Instant due) {
		jdbc.update("INSERT INTO alert_outbox (incident_id, channel_id, kind, next_attempt_at) VALUES (?, ?, ?, ?)",
				incidentId, slack, kind, utc(due));
	}

	private Map<String, Object> row() {
		return jdbc.queryForMap("SELECT attempts, next_attempt_at, sent_at, last_error FROM alert_outbox WHERE kind = 'DOWN'");
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
