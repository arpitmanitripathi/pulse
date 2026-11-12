package dev.pulse.statuspage;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import dev.pulse.statuspage.PublicStatusService.PublicStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** No login. Rate-limited per IP by RateLimitFilter (/api/public/**). */
@RestController
@Tag(name = "Public")
public class PublicStatusController {

	private final PublicStatusService service;

	public PublicStatusController(PublicStatusService service) {
		this.service = service;
	}

	@GetMapping("/api/public/status/{slug}")
	@Operation(summary = "Public status page data: state, uptime, 90 daily bars, 24 h latency, 30 days of incidents")
	public ResponseEntity<PublicStatus> get(@PathVariable String slug) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.maxAge(PublicStatusService.CACHE_TTL).cachePublic())
			.body(service.get(slug));
	}

}
