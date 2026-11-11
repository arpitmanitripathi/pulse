package dev.pulse.alert;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.pulse.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/alert-channels")
@Tag(name = "Alert channels")
@SecurityRequirement(name = "bearer")
public class AlertChannelController {

	private final AlertChannelService service;

	public AlertChannelController(AlertChannelService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Your alert channels (webhook URLs masked)")
	public List<ChannelResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return service.list(CurrentUser.id(jwt)).stream().map(ChannelResponse::from).toList();
	}

	@PostMapping
	@Operation(summary = "Add an email address, Slack webhook or Discord webhook (max 5)")
	public ResponseEntity<ChannelResponse> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateChannelRequest request) {
		AlertChannel channel = service.create(CurrentUser.id(jwt), request.type(), request.name(), request.target());
		return ResponseEntity.created(URI.create("/api/alert-channels/" + channel.getId()))
				.body(ChannelResponse.from(channel));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete a channel; its pending alerts are dropped")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		service.delete(CurrentUser.id(jwt), id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/test")
	@Operation(summary = "Send a test alert now (max 5 a minute)")
	public Map<String, Boolean> test(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		service.sendTest(CurrentUser.id(jwt), id);
		return Map.of("delivered", true);
	}

	public record CreateChannelRequest(
			@NotNull(message = "Type is required: EMAIL, SLACK or DISCORD.") AlertChannelType type,
			@NotBlank(message = "Name is required.") @Size(max = 100, message = "Name must be at most 100 characters.")
			String name,
			@NotBlank(message = "Target is required.") @Size(max = 2048, message = "Target is too long.")
			String target) {

		public CreateChannelRequest {
			name = name == null ? null : name.trim();
		}
	}

	public record ChannelResponse(UUID id, AlertChannelType type, String name, String target, Instant createdAt) {

		static ChannelResponse from(AlertChannel c) {
			return new ChannelResponse(c.getId(), c.getType(), c.getName(), c.maskedTarget(), c.getCreatedAt());
		}
	}

}
