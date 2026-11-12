package dev.pulse.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import dev.pulse.stats.StatsMath;
import dev.pulse.stats.StatsRepository;

/**
 * Turns raw check results into one hourly_rollups row per monitor and hour.
 *
 * <p>Each run covers every complete hour from the newest hour already rolled up (done
 * again, so results that arrived late are included) to the previous hour, at most
 * 7 days back (raw checks are deleted after that). Rows are upserted, so a second run
 * changes nothing and a missed run is repaired by the next one.
 */
@Component
public class RollupJob {

	static final Duration MAX_CATCH_UP = Duration.ofDays(7);

	private static final Logger log = LoggerFactory.getLogger(RollupJob.class);

	private static final String UPSERT = """
			INSERT INTO hourly_rollups (monitor_id, hour, checks, failures, latency_p50, latency_p95)
			SELECT monitor_id, hour, checks, failures, latency_p50, latency_p95 FROM (%s) AS hours
			ON CONFLICT (monitor_id, hour) DO UPDATE SET
			    checks = EXCLUDED.checks, failures = EXCLUDED.failures,
			    latency_p50 = EXCLUDED.latency_p50, latency_p95 = EXCLUDED.latency_p95
			""".formatted(StatsRepository.HOURLY_FROM_RAW.formatted(""));

	private final NamedParameterJdbcTemplate jdbc;
	private final AdvisoryLock lock;
	private final Clock clock;

	public RollupJob(NamedParameterJdbcTemplate jdbc, AdvisoryLock lock, Clock clock) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.clock = clock;
	}

	/** @return rows written, or -1 if another instance was already running it */
	public int runOnce() {
		int[] rows = { -1 };
		lock.runExclusively(AdvisoryLock.ROLLUP, () -> rows[0] = rollUp());
		return rows[0];
	}

	private int rollUp() {
		Instant to = StatsMath.startOfHour(clock.instant()); // the current hour is not complete
		Instant oldest = to.minus(MAX_CATCH_UP);
		OffsetDateTime newest = jdbc.queryForObject("SELECT max(hour) FROM hourly_rollups",
				new MapSqlParameterSource(), OffsetDateTime.class);
		Instant from = newest == null || newest.toInstant().isBefore(oldest) ? oldest : newest.toInstant();
		if (!from.isBefore(to)) {
			return 0;
		}
		int rows = jdbc.update(UPSERT, new MapSqlParameterSource()
				.addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
				.addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC)));
		log.info("rollup.done from={} to={} rows={}", from, to, rows);
		return rows;
	}

}
