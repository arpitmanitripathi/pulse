package dev.pulse.alert;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * What an alert says. Every channel gets the same words: a short title and a
 * few plain lines.
 *
 * @param reason     why it is down (the failing check's message), for DOWN
 * @param openedAt   when the incident opened
 * @param resolvedAt when it was resolved, for RECOVERED
 */
public record AlertMessage(AlertKind kind, String monitorName, String monitorUrl, String reason, Instant openedAt,
		Instant resolvedAt, String channelName) {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
			.withZone(ZoneOffset.UTC);

	public static AlertMessage test(String channelName) {
		return new AlertMessage(AlertKind.TEST, null, null, null, null, null, channelName);
	}

	public String title() {
		return switch (kind) {
			case DOWN -> "🔴 " + monitorName + " is down";
			case RECOVERED -> "✅ " + monitorName + " is back up";
			case TEST -> "Pulse test alert";
		};
	}

	public String body() {
		return switch (kind) {
			case DOWN -> monitorName + " is down.\n"
					+ "URL: " + monitorUrl + "\n"
					+ "Reason: " + reason + "\n"
					+ "Since: " + TIME.format(openedAt);
			case RECOVERED -> resolvedAt == null
					? monitorName + " is back up.\nURL: " + monitorUrl
					: monitorName + " is back up after " + humanDuration(Duration.between(openedAt, resolvedAt))
							+ ".\n"
							+ "URL: " + monitorUrl + "\n"
							+ "Down from " + TIME.format(openedAt) + " to " + TIME.format(resolvedAt) + ".";
			case TEST -> "This is a test alert from Pulse for the channel \"" + channelName + "\".\n"
					+ "If you can read this, alerts will reach you here.";
		};
	}

	/** Title and body together, for chat channels. */
	public String text() {
		return title() + "\n" + body();
	}

	static String humanDuration(Duration d) {
		long seconds = Math.max(0, d.toSeconds());
		if (seconds < 60) {
			return seconds + " s";
		}
		long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + " min";
		}
		long hours = minutes / 60;
		long rest = minutes % 60;
		return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
	}

}
