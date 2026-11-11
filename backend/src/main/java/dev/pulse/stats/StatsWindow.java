package dev.pulse.stats;

import java.time.Duration;
import java.time.Instant;

import dev.pulse.common.FieldValidationException;

/**
 * The three windows of docs/DESIGN.md: 24 h exact from raw checks, 7 d as 168 whole
 * UTC hours, 30 d as 30 whole UTC days. Both of the last two include the current hour or day.
 */
public enum StatsWindow {

	H24("24h"), D7("7d"), D30("30d");

	private final String label;

	StatsWindow(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** Where the window starts, given the current time. */
	public Instant from(Instant now) {
		return switch (this) {
			case H24 -> now.minus(Duration.ofHours(24));
			case D7 -> StatsMath.startOfHour(now).minus(Duration.ofHours(167));
			case D30 -> StatsMath.startOfDay(now).minus(Duration.ofDays(29));
		};
	}

	public static StatsWindow parse(String value) {
		for (StatsWindow w : values()) {
			if (w.label.equals(value)) {
				return w;
			}
		}
		throw new FieldValidationException("window", "Window must be 24h, 7d or 30d.");
	}

}
