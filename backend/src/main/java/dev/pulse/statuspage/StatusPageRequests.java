package dev.pulse.statuspage;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies for the status page API, with the limits from docs/DESIGN.md. */
public final class StatusPageRequests {

	/** Same rule as the database CHECK: 3–40 of a–z, 0–9 and "-", not starting or ending with "-". */
	static final String SLUG_PATTERN = "^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$";
	static final String SLUG = "Address must be 3 to 40 lower-case letters, digits or hyphens, "
			+ "and cannot start or end with a hyphen.";
	static final String TITLE = "Title must be 1 to 100 characters.";
	static final String MONITORS = "A status page can show at most 20 monitors.";

	private StatusPageRequests() {
	}

	public record CreateStatusPageRequest(
			@NotBlank(message = "Address is required.") @Pattern(regexp = SLUG_PATTERN, message = SLUG) String slug,
			@NotBlank(message = "Title is required.") @Size(max = 100, message = TITLE) String title,
			@NotNull(message = "Monitors are required (an empty list is fine).") @Size(max = 20, message = MONITORS)
			List<@NotNull(message = "Monitor ids cannot be null.") UUID> monitorIds) {

		public CreateStatusPageRequest {
			slug = slug == null ? null : slug.trim();
			title = title == null ? null : title.trim();
		}
	}

	/** PATCH body: only the fields that are present change. */
	public record UpdateStatusPageRequest(
			@Pattern(regexp = SLUG_PATTERN, message = SLUG) String slug,
			@Size(min = 1, max = 100, message = TITLE) @Pattern(regexp = "(?s).*\\S.*", message = TITLE) String title,
			@Size(max = 20, message = MONITORS) List<@NotNull(message = "Monitor ids cannot be null.") UUID> monitorIds) {

		public UpdateStatusPageRequest {
			slug = slug == null ? null : slug.trim();
			title = title == null ? null : title.trim();
		}
	}

}
