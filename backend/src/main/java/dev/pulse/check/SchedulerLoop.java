package dev.pulse.check;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * One tick: claim due monitors, then run each check on its own virtual thread.
 *
 * <p>The semaphore caps checks in flight. A tick never claims more monitors
 * than there are free slots, so a claimed monitor is always checked right away
 * instead of waiting in a queue while its next run comes due.
 */
@Component
public class SchedulerLoop implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);

	private final MonitorClaimer claimer;
	private final Checker checker;
	private final CheckResultProcessor processor;
	private final Clock clock;
	private final int batchSize;
	private final Semaphore slots;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public SchedulerLoop(MonitorClaimer claimer, Checker checker, CheckResultProcessor processor, Clock clock,
			SchedulerProperties properties) {
		this.claimer = claimer;
		this.checker = checker;
		this.processor = processor;
		this.clock = clock;
		this.batchSize = properties.batchSize();
		this.slots = new Semaphore(properties.maxInFlight());
	}

	/** Returns how many checks were started. Called by one thread at a time. */
	public int tick() {
		int limit = Math.min(batchSize, slots.availablePermits());
		if (limit <= 0) {
			return 0;
		}
		List<ClaimedMonitor> claimed = claimer.claimDue(clock.instant(), limit);
		for (ClaimedMonitor monitor : claimed) {
			slots.acquireUninterruptibly(); // never blocks: limit <= free slots, and only this thread acquires
			executor.submit(() -> run(monitor));
		}
		return claimed.size();
	}

	private void run(ClaimedMonitor monitor) {
		try {
			processor.record(monitor, checker.check(monitor));
		}
		catch (RuntimeException ex) {
			log.warn("check.record_failed monitor_id={} error={}", monitor.id(), ex.getClass().getSimpleName());
		}
		finally {
			slots.release();
		}
	}

	/** Stops taking work and waits up to 15 s for running checks to finish. */
	@Override
	@PreDestroy
	public void close() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		}
		catch (InterruptedException ex) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

}
