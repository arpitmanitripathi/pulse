package dev.pulse.monitor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.pulse.monitor.MonitorRequests.CreateMonitorRequest;
import dev.pulse.monitor.MonitorRequests.UpdateMonitorRequest;
import dev.pulse.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/monitors")
@Tag(name = "Monitors")
@SecurityRequirement(name = "bearer")
public class MonitorController {

	private final MonitorService service;

	public MonitorController(MonitorService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Your monitors, oldest first")
	public List<MonitorResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return service.list(CurrentUser.id(jwt)).stream().map(MonitorResponse::from).toList();
	}

	@PostMapping
	@Operation(summary = "Add a monitor (max 20). It is checked within about a second.")
	public ResponseEntity<MonitorResponse> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateMonitorRequest request) {
		Monitor monitor = service.create(CurrentUser.id(jwt), request);
		return ResponseEntity.created(URI.create("/api/monitors/" + monitor.getId()))
				.body(MonitorResponse.from(monitor));
	}

	@GetMapping("/{id}")
	@Operation(summary = "One monitor")
	public MonitorResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return MonitorResponse.from(service.get(CurrentUser.id(jwt), id));
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Change some fields. A new URL resets the state and checks it right away.")
	public MonitorResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody UpdateMonitorRequest request) {
		return MonitorResponse.from(service.update(CurrentUser.id(jwt), id, request));
	}

	@PostMapping("/{id}/pause")
	@Operation(summary = "Stop checking")
	public MonitorResponse pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return MonitorResponse.from(service.pause(CurrentUser.id(jwt), id));
	}

	@PostMapping("/{id}/resume")
	@Operation(summary = "Start checking again, from a fresh state")
	public MonitorResponse resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return MonitorResponse.from(service.resume(CurrentUser.id(jwt), id));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete the monitor and its history")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		service.delete(CurrentUser.id(jwt), id);
		return ResponseEntity.noContent().build();
	}

	public record MonitorResponse(UUID id, String name, String url, CheckMethod method, int intervalSeconds,
			int timeoutMs, Integer expectedStatus, boolean active, MonitorState state, Instant lastCheckedAt,
			Instant createdAt) {

		static MonitorResponse from(Monitor m) {
			return new MonitorResponse(m.getId(), m.getName(), m.getUrl(), m.getMethod(), m.getIntervalSeconds(),
					m.getTimeoutMs(), m.getExpectedStatus(), m.isActive(), m.getState(), m.getLastCheckedAt(),
					m.getCreatedAt());
		}
	}

}
