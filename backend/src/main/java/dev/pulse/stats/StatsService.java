package dev.pulse.stats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

/** Uptime and latency for the dashboard and the public status page. Rules: docs/DESIGN.md, "Stats rules". */
@Service
public class StatsService {

	private final StatsRepository repository;
	private final Clock clock;

	public StatsService(StatsRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/** 24 h uptime per monitor (exact, raw checks); monitors without checks map to null. */
	public Map<UUID, Double> uptime24h(Collection<UUID> monitorIds) {
		Instant now = clock.instant();
		Map<UUID, Bucket> totals = repository.rawTotals(monitorIds, StatsWindow.H24.from(now), now.plusNanos(1));
		Map<UUID, Double> uptime = new HashMap<>();
		for (UUID id : monitorIds) {
			Bucket b = totals.get(id);
			uptime.put(id, b == null ? null : b.uptime());
		}
		return uptime;
	}

	/** Stats for one monitor over one window. The caller has already checked ownership. */
	public MonitorStats stats(UUID monitorId, StatsWindow window) {
		Instant now = clock.instant();
		Instant from = window.from(now);
		List<UUID> ids = List.of(monitorId);
		return switch (window) {
			case H24 -> {
				Instant firstHour = StatsMath.startOfHour(now).minus(Duration.ofHours(23));
				Bucket total = repository.rawTotals(ids, from, now.plusNanos(1)).get(monitorId);
				List<Bucket> hours = repository.hourly(ids, firstHour, now).getOrDefault(monitorId, List.of());
				yield MonitorStats.of(window, from, now, total == null ? StatsMath.combine(from, List.of()) : total,
						"HOUR", StatsMath.hourly(hours, firstHour, 24));
			}
			case D7 -> {
				List<Bucket> hours = repository.hourly(ids, from, now).getOrDefault(monitorId, List.of());
				yield MonitorStats.of(window, from, now, StatsMath.combine(from, hours), "HOUR",
						StatsMath.hourly(hours, from, 168));
			}
			case D30 -> {
				List<Bucket> hours = repository.hourly(ids, from, now).getOrDefault(monitorId, List.of());
				yield MonitorStats.of(window, from, now, StatsMath.combine(from, hours), "DAY",
						StatsMath.daily(hours, from, 30));
			}
		};
	}

	/** Totals and series for one window. Uptime in %, latency in ms; nulls mean "no data". */
	public record MonitorStats(String window, Instant from, Instant to, int checks, int failures, Double uptime,
			Integer latencyP50, Integer latencyP95, String bucket, List<Point> series) {

		static MonitorStats of(StatsWindow window, Instant from, Instant to, Bucket total, String bucket,
				List<Bucket> series) {
			return new MonitorStats(window.label(), from, to, total.checks(), total.failures(), total.uptime(),
					total.latencyP50(), total.latencyP95(), bucket, series.stream().map(Point::of).toList());
		}
	}

	public record Point(Instant start, int checks, int failures, Double uptime, Integer latencyP50,
			Integer latencyP95) {

		static Point of(Bucket b) {
			return new Point(b.start(), b.checks(), b.failures(), b.uptime(), b.latencyP50(), b.latencyP95());
		}
	}

}
