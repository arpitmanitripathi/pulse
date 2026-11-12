package dev.pulse.stats;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads check counts and latency percentiles. The last 24 whole hours come from raw
 * check results, older hours from hourly_rollups (raw checks are deleted after 7 days).
 */
@Repository
public class StatsRepository {

	/** How many of the newest hours are read from raw checks instead of rollups. */
	static final int RAW_HOURS = 24;

	/** One row per monitor and UTC hour. Also the rollup job's source query. */
	public static final String HOURLY_FROM_RAW = """
			SELECT monitor_id, date_trunc('hour', checked_at, 'UTC') AS hour,
			       count(*) AS checks,
			       count(*) FILTER (WHERE NOT success) AS failures,
			       percentile_disc(0.5) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE success) AS latency_p50,
			       percentile_disc(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE success) AS latency_p95
			FROM check_results
			WHERE checked_at >= :from AND checked_at < :to %s
			GROUP BY monitor_id, hour
			""";

	private final NamedParameterJdbcTemplate jdbc;

	public StatsRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Exact totals over raw checks in [from, to), per monitor. Monitors without checks are missing. */
	public Map<UUID, Bucket> rawTotals(Collection<UUID> monitorIds, Instant from, Instant to) {
		Map<UUID, Bucket> totals = new HashMap<>();
		if (monitorIds.isEmpty()) {
			return totals;
		}
		jdbc.query("""
				SELECT monitor_id,
				       count(*) AS checks,
				       count(*) FILTER (WHERE NOT success) AS failures,
				       percentile_disc(0.5) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE success) AS latency_p50,
				       percentile_disc(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE success) AS latency_p95
				FROM check_results
				WHERE monitor_id IN (:ids) AND checked_at >= :from AND checked_at < :to
				GROUP BY monitor_id
				""", params(monitorIds, from, to), rs -> {
			totals.put(rs.getObject("monitor_id", UUID.class), new Bucket(from, rs.getInt("checks"),
					rs.getInt("failures"), integer(rs, "latency_p50"), integer(rs, "latency_p95")));
		});
		return totals;
	}

	/**
	 * Hourly buckets from {@code fromHour} up to and including the current hour, per
	 * monitor. Only hours with checks are returned.
	 */
	public Map<UUID, List<Bucket>> hourly(Collection<UUID> monitorIds, Instant fromHour, Instant now) {
		Map<UUID, List<Bucket>> hours = new HashMap<>();
		if (monitorIds.isEmpty()) {
			return hours;
		}
		Instant end = StatsMath.startOfHour(now).plus(Duration.ofHours(1));
		Instant rawFrom = end.minus(Duration.ofHours(RAW_HOURS));
		if (fromHour.isBefore(rawFrom)) {
			jdbc.query("""
					SELECT monitor_id, hour, checks, failures, latency_p50, latency_p95
					FROM hourly_rollups
					WHERE monitor_id IN (:ids) AND hour >= :from AND hour < :to
					""", params(monitorIds, fromHour, rawFrom), rs -> {
				add(hours, rs);
			});
		}
		Instant from = fromHour.isAfter(rawFrom) ? fromHour : rawFrom;
		jdbc.query(HOURLY_FROM_RAW.formatted("AND monitor_id IN (:ids)"), params(monitorIds, from, end), rs -> {
			add(hours, rs);
		});
		return hours;
	}

	private static void add(Map<UUID, List<Bucket>> hours, ResultSet rs) throws SQLException {
		hours.computeIfAbsent(rs.getObject("monitor_id", UUID.class), k -> new ArrayList<>())
			.add(new Bucket(rs.getObject("hour", OffsetDateTime.class).toInstant(), rs.getInt("checks"),
					rs.getInt("failures"), integer(rs, "latency_p50"), integer(rs, "latency_p95")));
	}

	private static MapSqlParameterSource params(Collection<UUID> ids, Instant from, Instant to) {
		return new MapSqlParameterSource("ids", ids).addValue("from", utc(from)).addValue("to", utc(to));
	}

	private static Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
