package dev.pulse.statuspage;

import java.net.URI;
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

import dev.pulse.security.CurrentUser;
import dev.pulse.statuspage.StatusPageRequests.CreateStatusPageRequest;
import dev.pulse.statuspage.StatusPageRequests.UpdateStatusPageRequest;
import dev.pulse.statuspage.StatusPageService.StatusPageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/status-pages")
@Tag(name = "Status pages")
@SecurityRequirement(name = "bearer")
public class StatusPageController {

	private final StatusPageService service;

	public StatusPageController(StatusPageService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Your status pages, oldest first")
	public List<StatusPageView> list(@AuthenticationPrincipal Jwt jwt) {
		return service.list(CurrentUser.id(jwt));
	}

	@PostMapping
	@Operation(summary = "Create a status page (max 3) showing up to 20 of your monitors, in the given order")
	public ResponseEntity<StatusPageView> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateStatusPageRequest request) {
		StatusPageView page = service.create(CurrentUser.id(jwt), request);
		return ResponseEntity.created(URI.create("/api/status-pages/" + page.id())).body(page);
	}

	@GetMapping("/{id}")
	@Operation(summary = "One status page")
	public StatusPageView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.get(CurrentUser.id(jwt), id);
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Change the address, title or monitors. The public page updates at once.")
	public StatusPageView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody UpdateStatusPageRequest request) {
		return service.update(CurrentUser.id(jwt), id, request);
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete the status page")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		service.delete(CurrentUser.id(jwt), id);
		return ResponseEntity.noContent().build();
	}

}
