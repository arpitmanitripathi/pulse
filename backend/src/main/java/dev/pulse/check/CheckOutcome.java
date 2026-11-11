package dev.pulse.check;

import java.time.Instant;

/** The result of one check, ready to be stored. */
public record CheckOutcome(Instant checkedAt, boolean success, Integer statusCode, int latencyMs,
		ErrorType errorType, String errorMessage) {

	static final int MAX_MESSAGE = 500;

	public static CheckOutcome success(Instant checkedAt, int statusCode, int latencyMs) {
		return new CheckOutcome(checkedAt, true, statusCode, latencyMs, ErrorType.NONE, null);
	}

	public static CheckOutcome failure(Instant checkedAt, Integer statusCode, int latencyMs, ErrorType type,
			String message) {
		String trimmed = message == null || message.length() <= MAX_MESSAGE ? message
				: message.substring(0, MAX_MESSAGE);
		return new CheckOutcome(checkedAt, false, statusCode, latencyMs, type, trimmed);
	}

}
