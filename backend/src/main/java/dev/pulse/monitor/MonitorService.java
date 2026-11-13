package dev.pulse.monitor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.pulse.auth.UserRepository;
import dev.pulse.common.ApiException;
import dev.pulse.common.FieldValidationException;
import dev.pulse.monitor.MonitorRequests.CreateMonitorRequest;
import dev.pulse.monitor.MonitorRequests.UpdateMonitorRequest;
import dev.pulse.security.SsrfGuard;
import dev.pulse.security.SsrfGuard.UnsafeUrlException;
import dev.pulse.statuspage.PublicStatusService;

@Service
public class MonitorService {

	public static final int MAX_MONITORS_PER_USER = 20;
	static final int DEFAULT_TIMEOUT_MS = 5000;

	private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

	private final MonitorRepository monitors;
	private final UserRepository users;
	private final SsrfGuard ssrfGuard;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final PublicStatusService publicStatus;

	public MonitorService(MonitorRepository monitors, UserRepository users, SsrfGuard ssrfGuard, JdbcTemplate jdbc,
			Clock clock, PublicStatusService publicStatus) {
		this.monitors = monitors;
		this.users = users;
		this.ssrfGuard = ssrfGuard;
		this.jdbc = jdbc;
		this.clock = clock;
		this.publicStatus = publicStatus;
	}

	@Transactional(readOnly = true)
	public List<Monitor> list(UUID userId) {
		return monitors.findAllByUserIdOrderByCreatedAtAsc(userId);
	}

	@Transactional(readOnly = true)
	public Monitor get(UUID userId, UUID monitorId) {
		return find(userId, monitorId);
	}

	@Transactional
	public Monitor create(UUID userId, CreateMonitorRequest request) {
		checkUrlIsSafe(request.url());
		// Lock the user row first: parallel creates by the same user now run one at a time,
		// so the count below can't be stale.
		users.lockById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found."));
		if (monitors.countByUserId(userId) >= MAX_MONITORS_PER_USER) {
			throw new ApiException(HttpStatus.CONFLICT,
					"You can have at most " + MAX_MONITORS_PER_USER + " monitors. Delete one to add another.");
		}
		Monitor monitor = monitors.save(new Monitor(userId, request.name(), request.url(),
				request.method() == null ? CheckMethod.GET : CheckMethod.valueOf(request.method()),
				request.intervalSeconds(),
				request.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : request.timeoutMs(),
				request.expectedStatus(), clock.instant()));
		log.info("monitor.created monitor_id={} user_id={}", monitor.getId(), userId);
		return monitor;
	}

	@Transactional
	public Monitor update(UUID userId, UUID monitorId, UpdateMonitorRequest request) {
		Monitor monitor = find(userId, monitorId);
		if (request.getUrl() != null) {
			checkUrlIsSafe(request.getUrl());
			if (monitor.changeUrl(request.getUrl(), clock.instant())) {
				closeOpenIncident(monitor.getId());
			}
		}
		if (request.getName() != null) {
			monitor.rename(request.getName());
		}
		if (request.getMethod() != null) {
			monitor.changeMethod(CheckMethod.valueOf(request.getMethod()));
		}
		if (request.getIntervalSeconds() != null) {
			monitor.changeInterval(request.getIntervalSeconds());
		}
		if (request.getTimeoutMs() != null) {
			monitor.changeTimeout(request.getTimeoutMs());
		}
		if (request.hasExpectedStatus()) {
			monitor.changeExpectedStatus(request.getExpectedStatus());
		}
		publicStatus.evictPagesShowing(monitor.getId());
		return monitor;
	}

	@Transactional
	public Monitor pause(UUID userId, UUID monitorId) {
		Monitor monitor = find(userId, monitorId);
		if (monitor.isActive()) {
			monitor.pause();
			closeOpenIncident(monitor.getId()); // the outage ends where monitoring stops
			publicStatus.evictPagesShowing(monitor.getId());
		}
		return monitor;
	}

	@Transactional
	public Monitor resume(UUID userId, UUID monitorId) {
		Monitor monitor = find(userId, monitorId);
		if (monitor.resume(clock.instant())) {
			closeOpenIncident(monitor.getId());
			publicStatus.evictPagesShowing(monitor.getId());
		}
		return monitor;
	}

	@Transactional
	public void delete(UUID userId, UUID monitorId) {
		Monitor monitor = find(userId, monitorId);
		publicStatus.evictPagesShowing(monitor.getId()); // before the delete removes the page links
		monitors.delete(monitor);
		log.info("monitor.deleted monitor_id={} user_id={}", monitorId, userId);
	}

	/**
	 * Ends an open incident without a RECOVERED alert: nothing recovered, the user paused the
	 * monitor, resumed it or changed what is monitored. Recorded as MONITOR_CHANGED.
	 */
	private void closeOpenIncident(UUID monitorId) {
		Instant now = clock.instant();
		int closed = jdbc.update(
				"UPDATE incidents SET resolved_at = ?, resolution = 'MONITOR_CHANGED' WHERE monitor_id = ? AND resolved_at IS NULL",
				OffsetDateTime.ofInstant(now, ZoneOffset.UTC), monitorId);
		if (closed > 0) {
			log.info("incident.closed_by_change monitor_id={}", monitorId);
		}
	}

	/** Another user's monitor gives the same 404 as one that doesn't exist, so ids can't be probed. */
	private Monitor find(UUID userId, UUID monitorId) {
		return monitors.findByIdAndUserId(monitorId, userId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Monitor not found."));
	}

	private void checkUrlIsSafe(String url) {
		try {
			ssrfGuard.check(url);
		}
		catch (UnsafeUrlException ex) {
			throw new FieldValidationException("url", ex.getMessage());
		}
	}

}
