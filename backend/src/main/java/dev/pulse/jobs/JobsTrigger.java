package dev.pulse.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Rollup hourly at :05 UTC, retention daily at 03:00 UTC (off when the scheduler is switched off, e.g. in tests). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pulse.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class JobsTrigger {

	private static final Logger log = LoggerFactory.getLogger(JobsTrigger.class);

	private final RollupJob rollup;
	private final RetentionJob retention;

	public JobsTrigger(RollupJob rollup, RetentionJob retention) {
		this.rollup = rollup;
		this.retention = retention;
	}

	@Scheduled(cron = "0 5 * * * *", zone = "UTC")
	public void rollup() {
		try {
			rollup.runOnce();
		}
		catch (RuntimeException ex) {
			log.warn("rollup.failed error={}", ex.toString());
		}
	}

	@Scheduled(cron = "0 0 3 * * *", zone = "UTC")
	public void retention() {
		try {
			retention.runOnce();
		}
		catch (RuntimeException ex) {
			log.warn("retention.failed error={}", ex.toString());
		}
	}

}
