package dev.pulse.monitor;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "monitors")
public class Monitor {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String url;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CheckMethod method;

	@Column(name = "interval_s", nullable = false)
	private int intervalSeconds;

	@Column(name = "timeout_ms", nullable = false)
	private int timeoutMs;

	@Column(name = "expected_status")
	private Integer expectedStatus;

	@Column(nullable = false)
	private boolean active;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MonitorState state;

	@Column(name = "consecutive_failures", nullable = false)
	private int consecutiveFailures;

	@Column(name = "consecutive_successes", nullable = false)
	private int consecutiveSuccesses;

	@Column(name = "next_check_at", nullable = false)
	private Instant nextCheckAt;

	@Column(name = "last_checked_at")
	private Instant lastCheckedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Monitor() {
		// for JPA
	}

	public Monitor(UUID userId, String name, String url, CheckMethod method, int intervalSeconds, int timeoutMs,
			Integer expectedStatus, Instant now) {
		this.userId = userId;
		this.name = name;
		this.url = url;
		this.method = method;
		this.intervalSeconds = intervalSeconds;
		this.timeoutMs = timeoutMs;
		this.expectedStatus = expectedStatus;
		this.active = true;
		this.createdAt = now;
		resetState(now);
	}

	/** Forget earlier results and check as soon as possible. */
	void resetState(Instant now) {
		this.state = MonitorState.UNKNOWN;
		this.consecutiveFailures = 0;
		this.consecutiveSuccesses = 0;
		this.nextCheckAt = now;
	}

	void rename(String name) {
		this.name = name;
	}

	void changeUrl(String url, Instant now) {
		if (!url.equals(this.url)) {
			this.url = url;
			resetState(now);
		}
	}

	void changeMethod(CheckMethod method) {
		this.method = method;
	}

	void changeInterval(int intervalSeconds) {
		this.intervalSeconds = intervalSeconds;
	}

	void changeTimeout(int timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	void changeExpectedStatus(Integer expectedStatus) {
		this.expectedStatus = expectedStatus;
	}

	void pause() {
		this.active = false;
	}

	void resume(Instant now) {
		if (!active) {
			this.active = true;
			resetState(now);
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public String getUrl() {
		return url;
	}

	public CheckMethod getMethod() {
		return method;
	}

	public int getIntervalSeconds() {
		return intervalSeconds;
	}

	public int getTimeoutMs() {
		return timeoutMs;
	}

	public Integer getExpectedStatus() {
		return expectedStatus;
	}

	public boolean isActive() {
		return active;
	}

	public MonitorState getState() {
		return state;
	}

	public Instant getNextCheckAt() {
		return nextCheckAt;
	}

	public Instant getLastCheckedAt() {
		return lastCheckedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
