package dev.pulse.check;

import java.time.Instant;
import java.util.UUID;

import dev.pulse.monitor.CheckMethod;

/**
 * A monitor the scheduler has claimed for one check: everything the checker
 * needs, copied out of the database so no transaction stays open during the check.
 *
 * @param scheduledAt when this check was due (used later to measure scheduling delay)
 */
public record ClaimedMonitor(UUID id, String url, CheckMethod method, int timeoutMs, Integer expectedStatus,
		Instant scheduledAt) {
}
