package dev.pulse.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the alert dispatcher every 5 seconds (off when the scheduler is switched off, e.g. in tests). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pulse.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AlertTrigger {

	private static final Logger log = LoggerFactory.getLogger(AlertTrigger.class);

	private final AlertOutboxDispatcher dispatcher;

	public AlertTrigger(AlertOutboxDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelay = 5000, initialDelay = 5000)
	public void dispatch() {
		try {
			dispatcher.dispatchDue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (RuntimeException ex) {
			log.warn("alert.dispatch_failed error={}", ex.getClass().getSimpleName());
		}
	}

}
