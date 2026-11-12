package dev.pulse.statuspage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.pulse.common.ApiException;
import dev.pulse.monitor.MonitorState;
import dev.pulse.stats.Bucket;
import dev.pulse.stats.StatsMath;
import dev.pulse.stats.StatsRepository;
import dev.pulse.stats.StatsService;
import dev.pulse.stats.StatsWindow;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the anonymous status page and caches it in Redis for 30 s. It contains names,
 * states, uptime, latency and incident times only: no URLs, ids, error details or AI text.
 * If Redis is unavailable the page is built on every request instead of failing.
 */
@Service
public class PublicStatusService {

	static final Duration CACHE_TTL = Duration.ofSeconds(30);
	static final int DAYS = 90;
	static final int INCIDENT_DAYS = 30;
	static final int MAX_INCIDENTS = 50;

	private static final Logger log = LoggerFactory.getLogger(PublicStatusService.class);

	private final StatusPageRepository pages;
	private final JdbcTemplate jdbc;
	private final StatsService stats;
	private final StatsRepository statsRepository;
	private final StringRedisTemplate redis;
	private final JsonMapper json;
	private final Clock clock;

	public PublicStatusService(StatusPageRepository pages, JdbcTemplate jdbc, StatsService stats,
			StatsRepository statsRepository, StringRedisTemplate redis, JsonMapper json, Clock clock) {
		this.pages = pages;
		this.jdbc = jdbc;
		this.stats = stats;
		this.statsRepository = statsRepository;
		this.redis = redis;
		this.json = json;
		this.clock = clock;
	}

	public PublicStatus get(String slug) {
		if (!slug.matches(StatusPageRequests.SLUG_PATTERN)) {
			throw notFound(); // can't exist: no database or cache lookup
		}
		String cached = readCache(slug);
		if (cached != null) {
			return json.readValue(cached, PublicStatus.class);
		}
		PublicStatus status = build(slug);
		writeCache(slug, json.writeValueAsString(status));
		return status;
	}

	/**
	 * Clears the cache of every status page that shows this monitor, after the current transaction
	 * commits. Called when a monitor is changed, paused, resumed or deleted. The slugs are read now,
	 * because a delete removes the page links.
	 */
	public void evictPagesShowing(UUID monitorId) {
		List<String> slugs = jdbc.queryForList("""
				SELECT sp.slug FROM status_pages sp JOIN status_page_monitors spm ON spm.status_page_id = sp.id
				WHERE spm.monitor_id = ?""", String.class, monitorId);
		if (slugs.isEmpty()) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					slugs.forEach(PublicStatusService.this::evict);
				}
			});
		}
		else {
			slugs.forEach(this::evict);
		}
	}

	void evict(String slug) {
		try {
			redis.delete(key(slug));
		}
		catch (RuntimeException ex) {
			log.warn("status_cache.evict_failed slug={} error={}", slug, ex.getClass().getSimpleName());
		}
	}

	private PublicStatus build(String slug) {
		StatusPage page = pages.findBySlug(slug)
			.orElseThrow(PublicStatusService::notFound);
		Instant now = clock.instant();
		List<PageMonitor> monitors = jdbc.query("""
				SELECT m.id, m.name, m.state, m.active
				FROM status_page_monitors spm JOIN monitors m ON m.id = spm.monitor_id
				WHERE spm.status_page_id = ? ORDER BY spm.position
				""", (rs, i) -> new PageMonitor(rs.getObject("id", UUID.class), rs.getString("name"),
				rs.getBoolean("active") ? rs.getString("state") : "PAUSED"), page.getId());
		List<UUID> ids = monitors.stream().map(PageMonitor::id).toList();

		Instant firstDay = StatsMath.startOfDay(now).minus(Duration.ofDays(DAYS - 1));
		Instant firstHour = StatsMath.startOfHour(now).minus(Duration.ofHours(23));
		Instant from7d = StatsWindow.D7.from(now);
		Instant from30d = StatsWindow.D30.from(now);
		Map<UUID, Double> uptime24h = stats.uptime24h(ids);
		Map<UUID, List<Bucket>> hours = statsRepository.hourly(ids, firstDay, now);

		List<PublicMonitor> result = monitors.stream().map(m -> {
			List<Bucket> h = hours.getOrDefault(m.id(), List.of());
			return new PublicMonitor(m.name(), m.state(), uptime24h.get(m.id()),
					StatsMath.combine(from7d, since(h, from7d)).uptime(),
					StatsMath.combine(from30d, since(h, from30d)).uptime(),
					StatsMath.daily(h, firstDay, DAYS).stream()
						.map(d -> new DayBar(LocalDate.ofInstant(d.start(), ZoneOffset.UTC), d.uptime()))
						.toList(),
					StatsMath.hourly(since(h, firstHour), firstHour, 24).stream()
						.map(b -> new LatencyPoint(b.start(), b.latencyP50(), b.latencyP95()))
						.toList());
		}).toList();

		List<PublicIncident> incidents = jdbc.query("""
				SELECT m.name, i.opened_at, i.resolved_at, i.resolution
				FROM incidents i
				JOIN status_page_monitors spm ON spm.monitor_id = i.monitor_id AND spm.status_page_id = ?
				JOIN monitors m ON m.id = i.monitor_id
				WHERE i.opened_at >= ? OR i.resolved_at IS NULL
				ORDER BY i.opened_at DESC
				LIMIT ?
				""", (rs, i) -> new PublicIncident(rs.getString("name"),
				rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
				toInstant(rs.getObject("resolved_at", OffsetDateTime.class)), rs.getString("resolution")), page.getId(),
				OffsetDateTime.ofInstant(now.minus(Duration.ofDays(INCIDENT_DAYS)), ZoneOffset.UTC), MAX_INCIDENTS);

		return new PublicStatus(page.getTitle(), result, incidents, now);
	}

	private static List<Bucket> since(List<Bucket> hours, Instant from) {
		return hours.stream().filter(b -> !b.start().isBefore(from)).toList();
	}

	private String readCache(String slug) {
		try {
			return redis.opsForValue().get(key(slug));
		}
		catch (RuntimeException ex) {
			log.warn("status_cache.read_failed error={}", ex.getClass().getSimpleName());
			return null;
		}
	}

	private void writeCache(String slug, String value) {
		try {
			redis.opsForValue().set(key(slug), value, CACHE_TTL);
		}
		catch (RuntimeException ex) {
			log.warn("status_cache.write_failed error={}", ex.getClass().getSimpleName());
		}
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "Status page not found.");
	}

	static String key(String slug) {
		return "status:" + slug;
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private record PageMonitor(UUID id, String name, String state) {
	}

	/** Everything an anonymous visitor sees. Uptime in %, latency in ms; null means no data. */
	public record PublicStatus(String title, List<PublicMonitor> monitors, List<PublicIncident> incidents,
			Instant generatedAt) {
	}

	/** {@code state} is one of {@link MonitorState} or PAUSED. */
	public record PublicMonitor(String name, String state, Double uptime24h, Double uptime7d, Double uptime30d,
			List<DayBar> days, List<LatencyPoint> latency24h) {
	}

	public record DayBar(LocalDate date, Double uptime) {
	}

	public record LatencyPoint(Instant hour, Integer latencyP50, Integer latencyP95) {
	}

	/**
	 * {@code resolvedAt} is null while the incident is ongoing. {@code resolution}: RECOVERED, or
	 * MONITOR_CHANGED when monitoring was paused or moved before it recovered.
	 */
	public record PublicIncident(String monitorName, Instant openedAt, Instant resolvedAt, String resolution) {
	}

}
