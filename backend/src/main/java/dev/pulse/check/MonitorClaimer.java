package dev.pulse.check;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import dev.pulse.monitor.CheckMethod;

/**
 * Picks the monitors that are due and moves their next check forward, in one
 * SQL statement.
 *
 * <p>FOR UPDATE SKIP LOCKED is what makes several app instances safe: a row
 * that another instance is claiming at this moment is skipped instead of
 * waited for, so two instances never claim the same monitor.
 */
@Component
public class MonitorClaimer {

	private static final String CLAIM_SQL = """
			WITH due AS (
			    SELECT id, next_check_at AS scheduled_at
			    FROM monitors
			    WHERE active AND next_check_at <= :now
			    ORDER BY next_check_at
			    LIMIT :limit
			    FOR UPDATE SKIP LOCKED
			)
			UPDATE monitors m
			SET next_check_at = CASE
			        WHEN due.scheduled_at + make_interval(secs => m.interval_s) > :now
			            THEN due.scheduled_at + make_interval(secs => m.interval_s)
			        ELSE :now + make_interval(secs => m.interval_s)  -- far behind: don't try to catch up
			    END
			FROM due
			WHERE m.id = due.id
			RETURNING m.id, m.url, m.method, m.timeout_ms, m.expected_status, due.scheduled_at
			""";

	private final NamedParameterJdbcTemplate jdbc;

	public MonitorClaimer(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<ClaimedMonitor> claimDue(Instant now, int limit) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
				.addValue("limit", limit);
		return jdbc.query(CLAIM_SQL, params, (rs, i) -> new ClaimedMonitor(
				rs.getObject("id", UUID.class),
				rs.getString("url"),
				CheckMethod.valueOf(rs.getString("method")),
				rs.getInt("timeout_ms"),
				(Integer) rs.getObject("expected_status"),
				rs.getObject("scheduled_at", OffsetDateTime.class).toInstant()));
	}

}
