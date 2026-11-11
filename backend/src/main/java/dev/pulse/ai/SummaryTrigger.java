package dev.pulse.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the summary worker every 5 seconds (off when the scheduler is switched off, e.g. in tests). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pulse.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SummaryTrigger {

	private static final Logger log = LoggerFactory.getLogger(SummaryTrigger.class);

	private final SummaryWorker worker;

	public SummaryTrigger(SummaryWorker worker) {
		this.worker = worker;
	}

	@Scheduled(fixedDelay = 5000, initialDelay = 5000)
	public void run() {
		try {
			worker.runOnce();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (RuntimeException ex) {
			log.warn("summary.run_failed error={}", ex.getClass().getSimpleName());
		}
	}

}
