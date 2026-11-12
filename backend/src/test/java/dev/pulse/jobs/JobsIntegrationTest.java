package dev.pulse.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import dev.pulse.IntegrationTest;
import dev.pulse.support.MutableClock;
import dev.pulse.support.TestUsers;

@IntegrationTest
class JobsIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	NamedParameterJdbcTemplate named;

	@Autowired
	AdvisoryLock lock;

	@Autowired
	TestUsers testUsers;

	/** Five minutes into the current hour, like the real job at :05. */
	final Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofMinutes(5));
	final Instant h1 = now.minus(Duration.ofMinutes(125)); // start of the hour before last
	final Instant h2 = now.minus(Duration.ofMinutes(65));  // start of the last complete hour
	final MutableClock clock = new MutableClock(now);
	UUID monitor;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		UUID user = testUsers.create("alice@example.com").id();
		monitor = jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s) VALUES (?, 'API', 'https://example.com/', 60)
				RETURNING id""", UUID.class, user);
	}

	RollupJob rollup() {
		return new RollupJob(named, lock, clock);
	}

	@Test
	void rollupMatchesHandComputedValues() {
		check(h1.plusSeconds(600), true, 100);   // h1: successes 100, 200, 300 and one failure
		check(h1.plusSeconds(1200), true, 300);
		check(h1.plusSeconds(3000), true, 200);  // :50, same UTC hour (a local-time cut at :30 would split it)
		check(h1.plusSeconds(3599), false, 5000);
		check(h2.plusSeconds(10), false, 40);    // h2: only failures
		check(h2.plusSeconds(70), false, 40);
		check(now.minusSeconds(60), true, 10);   // current hour: not complete, not rolled up

		assertThat(rollup().runOnce()).isEqualTo(2);

		// percentile_disc over successes [100, 200, 300]: p50 = 200, p95 = 300; the 5000 ms failure is ignored
		assertThat(row(h1)).containsEntry("checks", 4).containsEntry("failures", 1)
			.containsEntry("latency_p50", 200).containsEntry("latency_p95", 300);
		assertThat(row(h2)).containsEntry("checks", 2).containsEntry("failures", 2)
			.containsEntry("latency_p50", null).containsEntry("latency_p95", null);
		assertThat(count("hourly_rollups")).isEqualTo(2);
	}

	@Test
	void runningAgainChangesNothingAndALateResultIsPickedUp() {
		check(h1.plusSeconds(10), true, 100);
		check(h2.plusSeconds(10), true, 100);
		rollup().runOnce();
		Map<String, Object> before = row(h1);

		rollup().runOnce();
		assertThat(row(h1)).isEqualTo(before);
		assertThat(count("hourly_rollups")).isEqualTo(2);

		check(h2.plusSeconds(3590), false, 40); // recorded after h2 was rolled up
		rollup().runOnce();
		assertThat(row(h2)).containsEntry("checks", 2).containsEntry("failures", 1);
	}

	@Test
	void aMissedRunIsCaughtUpButNotBeyondSevenDays() {
		check(now.minus(Duration.ofDays(8)), true, 100);  // older than raw retention: never rolled
		check(now.minus(Duration.ofDays(3)), true, 100);
		check(h1.plusSeconds(10), true, 100);

		assertThat(rollup().runOnce()).isEqualTo(2);

		clock.advance(Duration.ofHours(5)); // five hourly runs missed
		check(now.plus(Duration.ofHours(2)), false, 40);
		assertThat(rollup().runOnce()).isEqualTo(2); // the newest rolled hour again, plus the new one
		assertThat(count("hourly_rollups")).isEqualTo(3);
	}

	@Test
	void onlyOneInstanceRunsAJobAtATime() throws Exception {
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService other = Executors.newSingleThreadExecutor();
		Future<Boolean> first = other.submit(() -> lock.runExclusively(AdvisoryLock.ROLLUP, () -> {
			holding.countDown();
			await(release);
		}));
		assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(rollup().runOnce()).as("skipped while the other instance holds the lock").isEqualTo(-1);
		assertThat(lock.runExclusively(AdvisoryLock.RETENTION, () -> { })).as("other jobs are not blocked").isTrue();

		release.countDown();
		assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
		other.shutdown();
		assertThat(rollup().runOnce()).as("free again afterwards").isZero();
	}

	@Test
	void retentionDeletesOnlyOldRowsInBatches() {
		// 10,005 raw checks older than 7 days: more than one batch of 10,000.
		jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, latency_ms, error_type)
				SELECT ?, ?::timestamptz - make_interval(secs => g), true, 10, 'NONE' FROM generate_series(1, 10005) g
				""", monitor, utc(now.minus(RetentionJob.RAW_KEEP)));
		check(now.minus(RetentionJob.RAW_KEEP).plusSeconds(60), true, 10);
		check(now.minusSeconds(60), true, 10);
		rollupRow(now.minus(RetentionJob.ROLLUP_KEEP).minus(Duration.ofHours(1)));
		rollupRow(now.minus(RetentionJob.ROLLUP_KEEP).plus(Duration.ofHours(1)));

		assertThat(new RetentionJob(jdbc, lock, clock).runOnce()).isEqualTo(10_005 + 1);

		assertThat(count("check_results")).isEqualTo(2);
		assertThat(count("hourly_rollups")).isEqualTo(1);
	}

	// ---------- helpers ----------

	private void check(Instant at, boolean success, int latencyMs) {
		jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, status_code, latency_ms, error_type)
				VALUES (?, ?, ?, ?, ?, ?)""", monitor, utc(at), success, success ? 200 : 503, latencyMs,
				success ? "NONE" : "STATUS");
	}

	private void rollupRow(Instant hour) {
		jdbc.update("INSERT INTO hourly_rollups (monitor_id, hour, checks, failures) VALUES (?, ?, 60, 0)", monitor,
				utc(hour.truncatedTo(ChronoUnit.HOURS)));
	}

	private Map<String, Object> row(Instant hour) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT checks, failures, latency_p50, latency_p95 FROM hourly_rollups WHERE monitor_id = ? AND hour = ?
				""", monitor, utc(hour));
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
