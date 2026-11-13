package dev.pulse.incident;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.pulse.alert.AlertChannelType;
import dev.pulse.alert.AlertKind;
import dev.pulse.alert.AlertOutboxDispatcher.Delivery;
import dev.pulse.check.ErrorType;
import dev.pulse.common.ApiException;
import dev.pulse.common.FieldValidationException;
import dev.pulse.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Read-only: incidents are opened and resolved by the check processor. */
@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents")
@SecurityRequirement(name = "bearer")
public class IncidentController {

	static final int LIMIT = 100;

	private static final String SELECT = """
			SELECT i.id, i.monitor_id, m.name AS monitor_name, i.opened_at, i.resolved_at, i.resolution, i.first_error_type,
			       i.summary, i.summary_status
			FROM incidents i JOIN monitors m ON m.id = i.monitor_id
			""";

	private final JdbcTemplate jdbc;

	public IncidentController(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping
	@Operation(summary = "Your incidents, newest first (max 100). Filter by monitor and open/resolved.")
	public List<IncidentResponse> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) UUID monitorId, @RequestParam(required = false) String status) {
		StringBuilder sql = new StringBuilder(SELECT).append(" WHERE m.user_id = ?");
		List<Object> args = new ArrayList<>(List.of(CurrentUser.id(jwt)));
		if (monitorId != null) {
			sql.append(" AND i.monitor_id = ?");
			args.add(monitorId);
		}
		if (status != null) {
			switch (status) {
				case "open" -> sql.append(" AND i.resolved_at IS NULL");
				case "resolved" -> sql.append(" AND i.resolved_at IS NOT NULL");
				default -> throw new FieldValidationException("status", "Status must be open or resolved.");
			}
		}
		sql.append(" ORDER BY i.opened_at DESC LIMIT ").append(LIMIT);
		return jdbc.query(sql.toString(), (rs, i) -> new IncidentResponse(
				rs.getObject("id", UUID.class), rs.getObject("monitor_id", UUID.class), rs.getString("monitor_name"),
				rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
				toInstant(rs.getObject("resolved_at", OffsetDateTime.class)), rs.getString("resolution"),
				ErrorType.valueOf(rs.getString("first_error_type")), rs.getString("summary"),
				rs.getString("summary_status"), null), args.toArray());
	}

	@GetMapping("/{id}")
	@Operation(summary = "One incident, with the delivery state of each alert")
	public IncidentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		List<Delivery> deliveries = jdbc.query("""
				SELECT o.channel_id, coalesce(c.name, o.channel_name) AS name, coalesce(c.type, o.channel_type) AS type,
				       o.kind, o.attempts, o.sent_at, o.last_error
				FROM alert_outbox o LEFT JOIN alert_channels c ON c.id = o.channel_id -- deleted channels keep their history
				WHERE o.incident_id = ? ORDER BY name, o.kind
				""", (rs, i) -> new Delivery(rs.getObject("channel_id", UUID.class), rs.getString("name"),
				AlertChannelType.valueOf(rs.getString("type")), AlertKind.valueOf(rs.getString("kind")),
				rs.getInt("attempts"), toInstant(rs.getObject("sent_at", OffsetDateTime.class)),
				rs.getString("last_error"), rs.getObject("channel_id") == null), id);
		return jdbc.query(SELECT + " WHERE i.id = ? AND m.user_id = ?", (rs, i) -> new IncidentResponse(
				rs.getObject("id", UUID.class), rs.getObject("monitor_id", UUID.class), rs.getString("monitor_name"),
				rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
				toInstant(rs.getObject("resolved_at", OffsetDateTime.class)), rs.getString("resolution"),
				ErrorType.valueOf(rs.getString("first_error_type")), rs.getString("summary"),
				rs.getString("summary_status"), deliveries), id, CurrentUser.id(jwt))
			.stream().findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Incident not found."));
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	/**
	 * {@code resolution}: RECOVERED (2 good checks) or MONITOR_CHANGED (paused, resumed or new URL); null while open.
	 * {@code alerts} is only filled in for a single incident.
	 */
	public record IncidentResponse(UUID id, UUID monitorId, String monitorName, Instant openedAt, Instant resolvedAt,
			String resolution,
			ErrorType firstErrorType, String summary, String summaryStatus, List<Delivery> alerts) {
	}

}
