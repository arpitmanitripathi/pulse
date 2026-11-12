package dev.pulse.stats;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.pulse.monitor.MonitorService;
import dev.pulse.security.CurrentUser;
import dev.pulse.stats.StatsService.MonitorStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Monitors")
@SecurityRequirement(name = "bearer")
public class StatsController {

	private final MonitorService monitors;
	private final StatsService stats;

	public StatsController(MonitorService monitors, StatsService stats) {
		this.monitors = monitors;
		this.stats = stats;
	}

	@GetMapping("/api/monitors/{id}/stats")
	@Operation(summary = "Uptime and latency over 24h, 7d or 30d, with a chart series (hourly; daily for 30d)")
	public MonitorStats stats(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@RequestParam(defaultValue = "24h") String window) {
		StatsWindow parsed = StatsWindow.parse(window);
		monitors.get(CurrentUser.id(jwt), id); // 404 unless it is the caller's monitor
		return stats.stats(id, parsed);
	}

}
