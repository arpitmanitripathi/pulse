package dev.pulse.stats;

import java.time.Instant;

/**
 * Check counts and latency for one period (an hour, a day or a whole window).
 * The percentiles cover successful checks only and are null when there were none.
 */
public record Bucket(Instant start, int checks, int failures, Integer latencyP50, Integer latencyP95) {

	public int successes() {
		return checks - failures;
	}

	/** Successful checks ÷ all checks in %, rounded down to 2 decimals; null with no checks. */
	public Double uptime() {
		return StatsMath.uptime(checks, failures);
	}

}
