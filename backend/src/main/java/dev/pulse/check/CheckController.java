package dev.pulse.check;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.pulse.common.FieldValidationException;
import dev.pulse.monitor.MonitorService;
import dev.pulse.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Monitors")
@SecurityRequirement(name = "bearer")
public class CheckController {

	static final int MAX_PAGE_SIZE = 200;

	private final MonitorService monitors;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	public CheckController(MonitorService monitors, JdbcTemplate jdbc, Clock clock) {
		this.monitors = monitors;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@GetMapping("/api/monitors/{id}/checks")
	@Operation(summary = "Check results from the last 24 hours, newest first")
	public CheckPage checks(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		if (page < 0) {
			throw new FieldValidationException("page", "Page must be 0 or more.");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new FieldValidationException("size", "Size must be 1 to " + MAX_PAGE_SIZE + ".");
		}
		monitors.get(CurrentUser.id(jwt), id); // 404 unless it is the caller's monitor

		Instant since = clock.instant().minus(Duration.ofHours(24));
		// Fetch one extra row to know whether another page exists, without a count query.
		List<CheckResponse> rows = jdbc.query("""
				SELECT checked_at, success, status_code, latency_ms, error_type, error_message
				FROM check_results
				WHERE monitor_id = ? AND checked_at >= ?
				ORDER BY checked_at DESC, id DESC
				LIMIT ? OFFSET ?
				""", (rs, i) -> new CheckResponse(
				rs.getObject("checked_at", OffsetDateTime.class).toInstant(),
				rs.getBoolean("success"),
				(Integer) rs.getObject("status_code"),
				rs.getInt("latency_ms"),
				ErrorType.valueOf(rs.getString("error_type")),
				rs.getString("error_message")),
				id, OffsetDateTime.ofInstant(since, ZoneOffset.UTC), size + 1, (long) page * size);

		boolean hasMore = rows.size() > size;
		return new CheckPage(hasMore ? rows.subList(0, size) : rows, page, size, hasMore);
	}

	public record CheckResponse(Instant checkedAt, boolean success, Integer statusCode, int latencyMs,
			ErrorType errorType, String errorMessage) {
	}

	public record CheckPage(List<CheckResponse> items, int page, int size, boolean hasMore) {
	}

}
