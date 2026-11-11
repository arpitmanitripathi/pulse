package dev.pulse.check;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.pulse.IntegrationTest;
import dev.pulse.support.TestUsers;

@IntegrationTest
class SchedulerIntegrationTest {

	@RegisterExtension
	static WireMockExtension site = WireMockExtension.newInstance()
		.options(wireMockConfig().dynamicPort())
		.build();

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	@Autowired
	MonitorClaimer claimer;

	@Autowired
	CheckResultProcessor processor;

	// Whole seconds, so values read back from Postgres (microseconds) compare exactly.
	final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
	UUID userId;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		userId = testUsers.create("scheduler@example.com").id();
	}

	// ---------- claiming ----------

	@Test
	void onlyDueActiveMonitorsAreClaimed() {
		UUID dueEarly = insertMonitor(now.minusSeconds(30), true, 60);
		UUID dueLate = insertMonitor(now.minusSeconds(5), true, 60);
		insertMonitor(now.plusSeconds(10), true, 60);   // not due yet
		insertMonitor(now.minusSeconds(30), false, 60); // paused

		List<ClaimedMonitor> claimed = claimer.claimDue(now, 10);

		assertThat(claimed).extracting(ClaimedMonitor::id).containsExactlyInAnyOrder(dueEarly, dueLate);
		assertThat(claimed).filteredOn(c -> c.id().equals(dueEarly))
			.extracting(ClaimedMonitor::scheduledAt).containsExactly(now.minusSeconds(30));
	}

	@Test
	void claimingRespectsTheLimitAndOldestComeFirst() {
		UUID oldest = insertMonitor(now.minusSeconds(50), true, 60);
		for (int i = 0; i < 4; i++) {
			insertMonitor(now.minusSeconds(10), true, 60);
		}

		List<ClaimedMonitor> first = claimer.claimDue(now, 3);

		assertThat(first).hasSize(3).extracting(ClaimedMonitor::id).contains(oldest);
		assertThat(claimer.claimDue(now, 3)).hasSize(2);
		assertThat(claimer.claimDue(now, 3)).isEmpty();
	}

	@Test
	void theNextCheckMovesOneIntervalOnOrJumpsAheadWhenFarBehind() {
		UUID onTime = insertMonitor(now.minusSeconds(10), true, 60);
		UUID farBehind = insertMonitor(now.minus(Duration.ofHours(3)), true, 60);

		claimer.claimDue(now, 10);

		assertThat(nextCheckAt(onTime)).isEqualTo(now.plusSeconds(50));   // scheduled + 60 s
		assertThat(nextCheckAt(farBehind)).isEqualTo(now.plusSeconds(60)); // now + 60 s, no catch-up burst
	}

	// ---------- the core guarantee ----------

	@Test
	void twoSchedulersCheckEveryMonitorExactlyOnce() throws Exception {
		int total = 200;
		for (int i = 0; i < total; i++) {
			insertMonitor(now.minusSeconds(1), true, 60);
		}
		Map<UUID, AtomicInteger> checks = new ConcurrentHashMap<>();
		Checker counting = monitor -> {
			checks.computeIfAbsent(monitor.id(), id -> new AtomicInteger()).incrementAndGet();
			sleep(10); // keep both schedulers busy at the same time
			return CheckOutcome.success(clock.instant(), 200, 10);
		};
		SchedulerProperties small = new SchedulerProperties(false, 25, 500);
		AtomicInteger claimed = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);

		List<Thread> instances = new ArrayList<>();
		List<SchedulerLoop> loops = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			SchedulerLoop loop = new SchedulerLoop(claimer, counting, processor, clock, small);
			loops.add(loop);
			instances.add(Thread.ofPlatform().start(() -> {
				await(start);
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
				while (claimed.get() < total && System.nanoTime() < deadline) {
					claimed.addAndGet(loop.tick());
				}
			}));
		}
		start.countDown();
		for (Thread t : instances) {
			t.join();
		}
		loops.forEach(SchedulerLoop::close); // waits for running checks

		assertThat(claimed.get()).isEqualTo(total);
		assertThat(checks).hasSize(total);
		assertThat(checks.values()).allMatch(count -> count.get() == 1);
		assertThat(count("SELECT count(*) FROM check_results")).isEqualTo(total);
		assertThat(count("SELECT count(*) FROM monitors WHERE last_checked_at IS NULL")).isZero();
	}

	@Test
	void aTickNeverClaimsMoreThanTheFreeSlots() throws Exception {
		for (int i = 0; i < 10; i++) {
			insertMonitor(now.minusSeconds(1), true, 60);
		}
		CountDownLatch release = new CountDownLatch(1);
		Checker blocked = monitor -> {
			await(release);
			return CheckOutcome.success(clock.instant(), 200, 1);
		};
		SchedulerLoop loop = new SchedulerLoop(claimer, blocked, processor, clock,
				new SchedulerProperties(false, 100, 3));

		assertThat(loop.tick()).isEqualTo(3);
		assertThat(loop.tick()).isZero(); // all 3 slots busy: nothing is claimed and left waiting
		release.countDown();
		loop.close();

		assertThat(count("SELECT count(*) FROM check_results")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM monitors WHERE next_check_at <= now()")).isEqualTo(7);
	}

	// ---------- end to end ----------

	@Test
	void aRealCheckIsStoredWithItsResult() {
		site.stubFor(get("/health").willReturn(aResponse().withStatus(503)));
		UUID id = insertMonitor(now.minusSeconds(1), true, 60, "http://site.test:" + site.getPort() + "/health");
		SchedulerLoop loop = new SchedulerLoop(claimer, TestSites.checker(), processor, clock,
				new SchedulerProperties(false, 100, 10));

		assertThat(loop.tick()).isEqualTo(1);
		loop.close();

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT success, status_code, error_type, error_message FROM check_results WHERE monitor_id = ?", id);
		assertThat(row).containsEntry("success", false).containsEntry("status_code", 503)
			.containsEntry("error_type", "STATUS")
			.containsEntry("error_message", "Expected a 2xx status, got 503.");
		assertThat(jdbc.queryForObject("SELECT last_checked_at FROM monitors WHERE id = ?", OffsetDateTime.class, id))
			.isNotNull();
	}

	@Test
	void aResultForADeletedMonitorIsDropped() {
		ClaimedMonitor gone = new ClaimedMonitor(UUID.randomUUID(), "https://example.com/", null, 1000, null, now);

		processor.record(gone, CheckOutcome.success(now, 200, 5));

		assertThat(count("SELECT count(*) FROM check_results")).isZero();
	}

	@Test
	void aCrashingCheckerDoesNotStopTheLoop() {
		insertMonitor(now.minusSeconds(1), true, 60);
		Checker broken = monitor -> {
			throw new IllegalStateException("boom");
		};
		SchedulerLoop loop = new SchedulerLoop(claimer, broken, processor, clock,
				new SchedulerProperties(false, 100, 1));

		assertThat(loop.tick()).isEqualTo(1);
		loop.close(); // the slot was released even though the check threw
		assertThat(count("SELECT count(*) FROM check_results")).isZero();
	}

	// ---------- helpers ----------

	private UUID insertMonitor(Instant nextCheckAt, boolean active, int intervalSeconds) {
		return insertMonitor(nextCheckAt, active, intervalSeconds, "https://example.com/");
	}

	private UUID insertMonitor(Instant nextCheckAt, boolean active, int intervalSeconds, String url) {
		return jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s, active, next_check_at)
				VALUES (?, 'm', ?, ?, ?, ?) RETURNING id
				""", UUID.class, userId, url, intervalSeconds, active, OffsetDateTime.ofInstant(nextCheckAt,
				ZoneOffset.UTC));
	}

	private Instant nextCheckAt(UUID id) {
		return jdbc.queryForObject("SELECT next_check_at FROM monitors WHERE id = ?", OffsetDateTime.class, id)
			.toInstant();
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
