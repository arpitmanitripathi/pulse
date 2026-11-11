package dev.pulse.ai;

import java.time.Instant;
import java.util.List;

/** Everything the AI sees: the monitor and its most recent checks, newest first. */
public record SummaryInput(String monitorName, String url, Instant openedAt, List<CheckLine> checks) {

	public record CheckLine(Instant checkedAt, Integer statusCode, int latencyMs, String errorType,
			String errorMessage) {
	}

}
