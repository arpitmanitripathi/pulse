package dev.pulse.ai;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import dev.pulse.IntegrationTest;
import dev.pulse.check.CheckOutcome;
import dev.pulse.check.CheckResultProcessor;
import dev.pulse.check.ClaimedMonitor;
import dev.pulse.check.ErrorType;
import dev.pulse.monitor.CheckMethod;
import dev.pulse.support.MutableClock;
import dev.pulse.support.TestUsers;

@IntegrationTest
class SummaryWorkerIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	NamedParameterJdbcTemplate named;

	@Autowired
	TestUsers testUsers;

	@Autowired
	CheckResultProcessor processor;

	@Autowired
	IncidentSummarizer configuredSummarizer;

	final Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	final MutableClock clock = new MutableClock(start);
	final FakeSummarizer fake = new FakeSummarizer();
	final List<SummaryWorker> workers = new ArrayList<>();
	UUID userId;
	UUID monitorId;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		userId = testUsers.create("alice@example.com").id();
		monitorId = jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s, state, reset_at)
				VALUES (?, 'API', 'https://example.com/', 60, 'DOWN', ?) RETURNING id""", UUID.class, userId,
				OffsetDateTime.ofInstant(start.minus(Duration.ofDays(1)), ZoneOffset.UTC)); // started fresh a day ago
	}

	@AfterEach
	void tearDown() {
		workers.forEach(SummaryWorker::close);
	}

	@Test
	void theTestConfigurationHasNoKeySoSummariesAreOff() {
		assertThat(configuredSummarizer.enabled()).isFalse();
	}

	@Test
	void aPendingIncidentGetsASummaryFromTheLast20Checks() throws Exception {
		for (int i = 0; i < 25; i++) {
			check(start.minusSeconds(60L * (25 - i)), "check " + i);
		}
		UUID incident = incident();
		fake.answer = "API returns 503 since 14:03; the server is failing.";

		assertThat(worker().runOnce()).isEqualTo(1);

		assertThat(summary(incident)).containsEntry("summary_status", "DONE")
			.containsEntry("summary", "API returns 503 since 14:03; the server is failing.");
		SummaryInput seen = fake.inputs.get(0);
		assertThat(seen.monitorName()).isEqualTo("API");
		assertThat(seen.checks()).hasSize(20);
		assertThat(seen.checks().get(0).errorMessage()).isEqualTo("check 24"); // newest first
		assertThat(worker().runOnce()).isZero();                               // done: not claimed again
	}

	@Test
	void checksFromBeforeTheMonitorLastStartedFreshAreNotSent() throws Exception {
		check(start.minusSeconds(3600), "before the pause");
		check(start.minusSeconds(3000), "before the pause too");
		jdbc.update("UPDATE monitors SET reset_at = ? WHERE id = ?", // resumed 2 minutes ago
				OffsetDateTime.ofInstant(start.minusSeconds(120), ZoneOffset.UTC), monitorId);
		check(start.minusSeconds(60), "after resume 1");
		check(start, "after resume 2");
		incident();

		worker().runOnce();

		assertThat(fake.inputs.get(0).checks()).extracting(SummaryInput.CheckLine::errorMessage)
			.containsExactly("after resume 2", "after resume 1");
	}

	@Test
	void aFailureIsRetriedOnceAfter30SecondsThenMarkedFailed() throws Exception {
		UUID incident = incident();
		fake.failuresLeft.set(10);
		SummaryWorker worker = worker();

		worker.runOnce();
		assertThat(summary(incident)).containsEntry("summary_status", "PENDING").containsEntry("summary_attempts", 1);
		clock.advance(Duration.ofSeconds(29));
		assertThat(worker.runOnce()).as("not before 30 s").isZero();
		clock.advance(Duration.ofSeconds(1));
		worker.runOnce();

		assertThat(summary(incident)).containsEntry("summary_status", "FAILED").containsEntry("summary_attempts", 2);
		clock.advance(Duration.ofHours(1));
		assertThat(worker.runOnce()).isZero();
	}

	@Test
	void aRetryCanSucceed() throws Exception {
		UUID incident = incident();
		fake.failuresLeft.set(1);
		SummaryWorker worker = worker();

		worker.runOnce();
		clock.advance(Duration.ofSeconds(30));
		worker.runOnce();

		assertThat(summary(incident)).containsEntry("summary_status", "DONE");
	}

	@Test
	void withoutAKeyPendingIncidentsAreSkippedAndNothingIsSent() throws Exception {
		UUID pending = incident();
		UUID otherMonitor = jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s) VALUES (?, 'Web', 'https://example.com/', 60)
				RETURNING id""", UUID.class, userId); // one open incident per monitor
		UUID done = jdbc.queryForObject("""
				INSERT INTO incidents (monitor_id, opened_at, first_error_type, summary_status, summary)
				VALUES (?, now(), 'STATUS', 'DONE', 'old') RETURNING id""", UUID.class, otherMonitor);
		fake.enabled = false;

		assertThat(worker().runOnce()).isEqualTo(1);

		assertThat(summary(pending)).containsEntry("summary_status", "SKIPPED");
		assertThat(summary(done)).containsEntry("summary_status", "DONE").containsEntry("summary", "old");
		assertThat(fake.inputs).isEmpty();
	}

	@Test
	void aWorkerThatDiesMidCallIsRetriedAfterTheLease() throws Exception {
		UUID incident = incident();
		// Claimed by a worker that then died: attempts 1, leased for a minute.
		jdbc.update("UPDATE incidents SET summary_attempts = 1, summary_next_attempt_at = ? WHERE id = ?",
				OffsetDateTime.ofInstant(start.plusSeconds(60), ZoneOffset.UTC), incident);

		assertThat(worker().runOnce()).isZero();
		clock.advance(Duration.ofSeconds(61));
		assertThat(worker().runOnce()).isEqualTo(1);
		assertThat(summary(incident)).containsEntry("summary_status", "DONE");
	}

	@Test
	void twoWorkersNeverSummariseTheSameIncident() throws Exception {
		for (int i = 0; i < 10; i++) {
			UUID m = jdbc.queryForObject("""
					INSERT INTO monitors (user_id, name, url, interval_s) VALUES (?, ?, 'https://example.com/', 60)
					RETURNING id""", UUID.class, userId, "m" + i);
			jdbc.update("""
					INSERT INTO incidents (monitor_id, opened_at, first_error_type, summary_next_attempt_at)
					VALUES (?, ?, 'STATUS', ?)""", m, OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
					OffsetDateTime.ofInstant(start, ZoneOffset.UTC));
		}
		fake.delayMs = 50;
		SummaryWorker a = worker();
		SummaryWorker b = worker();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		int handled = 0;
		List<java.util.concurrent.Callable<Integer>> runs = List.of(a::runOnce, b::runOnce);
		for (Future<Integer> f : pool.invokeAll(runs)) {
			handled += f.get();
		}
		pool.shutdown();

		assertThat(handled).isEqualTo(10);
		assertThat(fake.callsPerMonitor.values()).allMatch(n -> n.get() == 1).hasSize(10);
	}

	@Test
	void anAiOutageNeverStopsTheAlert() throws Exception {
		jdbc.update("UPDATE monitors SET state = 'UP'");
		jdbc.update("INSERT INTO alert_channels (user_id, type, target, name) VALUES (?, 'EMAIL', 'a@example.com', 'E')",
				userId);
		for (int i = 0; i < 3; i++) {
			processor.record(new ClaimedMonitor(monitorId, "https://example.com/", CheckMethod.GET, 5000, null, start),
					CheckOutcome.failure(start.plusSeconds(i), 503, 40, ErrorType.STATUS, "got 503"));
		}
		fake.failuresLeft.set(10);
		SummaryWorker worker = worker();
		clock.advance(Duration.ofSeconds(2)); // the incident opened at the third check
		assertThat(worker.runOnce()).isEqualTo(1);
		clock.advance(Duration.ofSeconds(30));
		worker.runOnce();

		assertThat(jdbc.queryForObject("SELECT summary_status FROM incidents", String.class)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_outbox WHERE kind = 'DOWN'", Integer.class))
			.isEqualTo(1);
	}

	// ---------- helpers ----------

	private SummaryWorker worker() {
		SummaryWorker worker = new SummaryWorker(named, fake, clock);
		workers.add(worker);
		return worker;
	}

	private UUID incident() {
		return jdbc.queryForObject("""
				INSERT INTO incidents (monitor_id, opened_at, first_error_type, summary_next_attempt_at)
				VALUES (?, ?, 'STATUS', ?) RETURNING id""", UUID.class, monitorId,
				OffsetDateTime.ofInstant(start, ZoneOffset.UTC), OffsetDateTime.ofInstant(start, ZoneOffset.UTC));
	}

	private void check(Instant at, String message) {
		jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, status_code, latency_ms, error_type, error_message)
				VALUES (?, ?, false, 503, 40, 'STATUS', ?)""", monitorId, OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
				message);
	}

	private Map<String, Object> summary(UUID incident) {
		return jdbc.queryForMap("SELECT summary, summary_status, summary_attempts FROM incidents WHERE id = ?", incident);
	}

	static class FakeSummarizer implements IncidentSummarizer {

		volatile boolean enabled = true;
		volatile String answer = "A short summary.";
		volatile long delayMs;
		final AtomicInteger failuresLeft = new AtomicInteger();
		final List<SummaryInput> inputs = new CopyOnWriteArrayList<>();
		final Map<String, AtomicInteger> callsPerMonitor = new ConcurrentHashMap<>();

		@Override
		public boolean enabled() {
			return enabled;
		}

		@Override
		public String summarize(SummaryInput input) throws SummaryException {
			inputs.add(input);
			callsPerMonitor.computeIfAbsent(input.monitorName(), k -> new AtomicInteger()).incrementAndGet();
			if (delayMs > 0) {
				try {
					Thread.sleep(delayMs);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
				throw new SummaryException("The AI API answered HTTP 503.");
			}
			return answer;
		}

	}

}
