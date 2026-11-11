package dev.pulse.check;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled     run the 1-second tick (off in tests, which call tick() themselves)
 * @param batchSize   monitors claimed per tick
 * @param maxInFlight checks running at once on this instance
 */
@ConfigurationProperties("pulse.scheduler")
public record SchedulerProperties(boolean enabled, int batchSize, int maxInFlight) {
}
