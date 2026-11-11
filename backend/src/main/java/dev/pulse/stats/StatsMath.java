package dev.pulse.stats;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The arithmetic behind every uptime and latency number. Plain Java with no
 * database, so it is unit-tested against hand-computed values. Rules: docs/DESIGN.md, "Stats rules".
 */
public final class StatsMath {

	private StatsMath() {
	}

	/** Successful ÷ all in %, rounded down to 2 decimals (99.996 stays 99.99); null with no checks. */
	public static Double uptime(long checks, long failures) {
		if (checks == 0) {
			return null;
		}
		return ((checks - failures) * 10_000 / checks) / 100.0;
	}

	/**
	 * Joins several buckets into one. Counts add up; p50 and p95 are the hourly values
	 * averaged, weighted by each bucket's successful checks.
	 */
	public static Bucket combine(Instant start, List<Bucket> parts) {
		int checks = 0;
		int failures = 0;
		long weight = 0;
		long p50Sum = 0;
		long p95Sum = 0;
		for (Bucket b : parts) {
			checks += b.checks();
			failures += b.failures();
			if (b.latencyP50() != null && b.successes() > 0) {
				weight += b.successes();
				p50Sum += (long) b.latencyP50() * b.successes();
				p95Sum += (long) b.latencyP95() * b.successes();
			}
		}
		Integer p50 = weight == 0 ? null : (int) Math.round((double) p50Sum / weight);
		Integer p95 = weight == 0 ? null : (int) Math.round((double) p95Sum / weight);
		return new Bucket(start, checks, failures, p50, p95);
	}

	/** One bucket per hour from {@code firstHour}, {@code count} hours long; hours without data are empty. */
	public static List<Bucket> hourly(List<Bucket> hours, Instant firstHour, int count) {
		Map<Instant, Bucket> byHour = hours.stream().collect(Collectors.toMap(Bucket::start, Function.identity()));
		List<Bucket> dense = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			Instant hour = firstHour.plus(Duration.ofHours(i));
			dense.add(byHour.getOrDefault(hour, empty(hour)));
		}
		return dense;
	}

	/** One bucket per UTC day from {@code firstDay}, {@code count} days long, built from hourly buckets. */
	public static List<Bucket> daily(List<Bucket> hours, Instant firstDay, int count) {
		Map<Instant, List<Bucket>> byDay = hours.stream().collect(Collectors.groupingBy(StatsMath::startOfDay));
		List<Bucket> dense = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			Instant day = firstDay.plus(Duration.ofDays(i));
			dense.add(combine(day, byDay.getOrDefault(day, List.of())));
		}
		return dense;
	}

	public static Instant startOfHour(Instant t) {
		return t.truncatedTo(ChronoUnit.HOURS);
	}

	public static Instant startOfDay(Instant t) {
		return t.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
	}

	public static Instant startOfDay(Bucket b) {
		return startOfDay(b.start());
	}

	private static Bucket empty(Instant start) {
		return new Bucket(start, 0, 0, null, null);
	}

}
