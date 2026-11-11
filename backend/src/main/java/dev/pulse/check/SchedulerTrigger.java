package dev.pulse.check;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Calls {@link SchedulerLoop#tick()} every second, 1 s after the previous tick ended. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pulse.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerTrigger {

	private static final Logger log = LoggerFactory.getLogger(SchedulerTrigger.class);

	private final SchedulerLoop loop;

	public SchedulerTrigger(SchedulerLoop loop) {
		this.loop = loop;
	}

	@Scheduled(fixedDelay = 1000, initialDelay = 1000)
	public void tick() {
		try {
			loop.tick();
		}
		catch (RuntimeException ex) {
			// A database hiccup must not stop the schedule; the next tick tries again.
			log.warn("scheduler.tick_failed error={}", ex.getClass().getSimpleName());
		}
	}

}
