package dev.pulse.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes raw check results older than 7 days and hourly rollups older than 90 days.
 * Raw checks go 10,000 rows per statement, each its own transaction, so a large
 * backlog never becomes one long-running delete.
 */
@Component
public class RetentionJob {

	public static final Duration RAW_KEEP = Duration.ofDays(7);
	public static final Duration ROLLUP_KEEP = Duration.ofDays(90);
	static final int BATCH = 10_000;

	private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

	private final JdbcTemplate jdbc;
	private final AdvisoryLock lock;
	private final Clock clock;

	public RetentionJob(JdbcTemplate jdbc, AdvisoryLock lock, Clock clock) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.clock = clock;
	}

	/** @return rows deleted, or -1 if another instance was already running it */
	public long runOnce() {
		long[] deleted = { -1 };
		lock.runExclusively(AdvisoryLock.RETENTION, () -> deleted[0] = deleteOld());
		return deleted[0];
	}

	private long deleteOld() {
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		long checks = 0;
		int batch;
		do {
			batch = jdbc.update("""
					DELETE FROM check_results WHERE id IN (
					    SELECT id FROM check_results WHERE checked_at < ? LIMIT ?)
					""", now.minus(RAW_KEEP), BATCH);
			checks += batch;
		}
		while (batch == BATCH);
		int rollups = jdbc.update("DELETE FROM hourly_rollups WHERE hour < ?", now.minus(ROLLUP_KEEP));
		log.info("retention.done checks_deleted={} rollups_deleted={}", checks, rollups);
		return checks + rollups;
	}

}
