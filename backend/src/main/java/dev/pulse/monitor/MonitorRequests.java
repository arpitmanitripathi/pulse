package dev.pulse.monitor;

import dev.pulse.common.OneOf;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies for the monitor API, with the limits from docs/DESIGN.md. */
public final class MonitorRequests {

	static final String NAME_SIZE = "Name must be 1 to 100 characters.";
	static final String URL_SIZE = "URL must be at most 2048 characters.";
	static final String METHOD = "Method must be GET or HEAD.";
	static final String INTERVAL = "Interval must be 30, 60, 120 or 300 seconds.";
	static final String TIMEOUT = "Timeout must be between 1000 and 10000 ms.";
	static final String STATUS = "Expected status must be between 100 and 599.";

	private MonitorRequests() {
	}

	public record CreateMonitorRequest(
			@NotBlank(message = "Name is required.") @Size(max = 100, message = NAME_SIZE) String name,
			@NotBlank(message = "URL is required.") @Size(max = 2048, message = URL_SIZE) String url,
			@Pattern(regexp = "GET|HEAD", message = METHOD) String method,
			@NotNull(message = "Interval is required.") @OneOf(value = { 30, 60, 120, 300 }, message = INTERVAL)
			Integer intervalSeconds,
			@Min(value = 1000, message = TIMEOUT) @Max(value = 10000, message = TIMEOUT) Integer timeoutMs,
			@Min(value = 100, message = STATUS) @Max(value = 599, message = STATUS) Integer expectedStatus) {

		public CreateMonitorRequest {
			name = name == null ? null : name.trim();
			url = url == null ? null : url.trim();
		}
	}

	/**
	 * PATCH body: only the fields that are present change. A class with setters
	 * (not a record) so "expectedStatus": null can be told apart from a missing
	 * expectedStatus: Jackson only calls a setter for a field that is in the JSON.
	 */
	public static class UpdateMonitorRequest {

		@Size(min = 1, max = 100, message = NAME_SIZE)
		@Pattern(regexp = ".*\\S.*", message = NAME_SIZE)
		private String name;

		@Size(min = 1, max = 2048, message = URL_SIZE)
		private String url;

		@Pattern(regexp = "GET|HEAD", message = METHOD)
		private String method;

		@OneOf(value = { 30, 60, 120, 300 }, message = INTERVAL)
		private Integer intervalSeconds;

		@Min(value = 1000, message = TIMEOUT)
		@Max(value = 10000, message = TIMEOUT)
		private Integer timeoutMs;

		@Min(value = 100, message = STATUS)
		@Max(value = 599, message = STATUS)
		private Integer expectedStatus;

		private boolean expectedStatusPresent;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name == null ? null : name.trim();
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url == null ? null : url.trim();
		}

		public String getMethod() {
			return method;
		}

		public void setMethod(String method) {
			this.method = method;
		}

		public Integer getIntervalSeconds() {
			return intervalSeconds;
		}

		public void setIntervalSeconds(Integer intervalSeconds) {
			this.intervalSeconds = intervalSeconds;
		}

		public Integer getTimeoutMs() {
			return timeoutMs;
		}

		public void setTimeoutMs(Integer timeoutMs) {
			this.timeoutMs = timeoutMs;
		}

		public Integer getExpectedStatus() {
			return expectedStatus;
		}

		public void setExpectedStatus(Integer expectedStatus) {
			this.expectedStatus = expectedStatus;
			this.expectedStatusPresent = true;
		}

		boolean hasExpectedStatus() {
			return expectedStatusPresent;
		}

	}

}
