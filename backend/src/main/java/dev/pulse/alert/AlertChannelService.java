package dev.pulse.alert;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.pulse.auth.UserRepository;
import dev.pulse.common.ApiException;
import dev.pulse.common.FieldValidationException;
import dev.pulse.security.RateLimiter;
import dev.pulse.security.SsrfGuard;
import dev.pulse.security.SsrfGuard.UnsafeUrlException;

@Service
public class AlertChannelService {

	public static final int MAX_CHANNELS_PER_USER = 5;
	static final int TESTS_PER_MINUTE = 5;

	private static final Logger log = LoggerFactory.getLogger(AlertChannelService.class);
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	private final AlertChannelRepository channels;
	private final UserRepository users;
	private final SsrfGuard ssrfGuard;
	private final AlertSenders senders;
	private final RateLimiter rateLimiter;
	private final Clock clock;
	private final JdbcTemplate jdbc;

	public AlertChannelService(AlertChannelRepository channels, UserRepository users, SsrfGuard ssrfGuard,
			AlertSenders senders, RateLimiter rateLimiter, Clock clock, JdbcTemplate jdbc) {
		this.channels = channels;
		this.users = users;
		this.ssrfGuard = ssrfGuard;
		this.senders = senders;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<AlertChannel> list(UUID userId) {
		return channels.findAllByUserIdOrderByCreatedAtAsc(userId);
	}

	@Transactional
	public AlertChannel create(UUID userId, AlertChannelType type, String name, String rawTarget) {
		String target = validateTarget(type, rawTarget.trim());
		users.lockById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found."));
		if (channels.countByUserId(userId) >= MAX_CHANNELS_PER_USER) {
			throw new ApiException(HttpStatus.CONFLICT, "You can have at most " + MAX_CHANNELS_PER_USER
					+ " alert channels. Delete one to add another.");
		}
		AlertChannel channel = channels.save(new AlertChannel(userId, type, target, name, clock.instant()));
		log.info("alert_channel.created channel_id={} type={} user_id={}", channel.getId(), type, userId);
		return channel;
	}

	@Transactional
	public void delete(UUID userId, UUID channelId) {
		AlertChannel channel = find(userId, channelId);
		// Alerts still waiting to be sent to this channel are dropped.
		jdbc.update("DELETE FROM alert_outbox WHERE channel_id = ? AND sent_at IS NULL AND attempts < ?", channelId,
				AlertOutboxDispatcher.MAX_ATTEMPTS);
		// Alerts that were sent or given up stay in the incident's history; keep the channel's name and type,
		// because deleting the channel clears channel_id (ON DELETE SET NULL, migration V6).
		jdbc.update("UPDATE alert_outbox SET channel_name = ?, channel_type = ? WHERE channel_id = ?", channel.getName(),
				channel.getType().name(), channelId);
		channels.delete(channel);
	}

	/** Sends a test message right now, so the user knows the channel works. */
	public void sendTest(UUID userId, UUID channelId) {
		AlertChannel channel = find(userId, channelId);
		if (!rateLimiter.tryAcquire("alert-test", userId.toString(), TESTS_PER_MINUTE, Duration.ofMinutes(1))
				.allowed()) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
					"You can send at most " + TESTS_PER_MINUTE + " test alerts a minute.");
		}
		try {
			senders.send(channel.getType(), channel.getTarget(), AlertMessage.test(channel.getName()));
		}
		catch (AlertDeliveryException ex) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "The test alert was not delivered. " + ex.getMessage());
		}
	}

	private AlertChannel find(UUID userId, UUID channelId) {
		return channels.findByIdAndUserId(channelId, userId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Alert channel not found."));
	}

	private String validateTarget(AlertChannelType type, String target) {
		return switch (type) {
			case EMAIL -> {
				if (target.length() > 254 || !EMAIL.matcher(target).matches()) {
					throw new FieldValidationException("target", "Enter a valid email address.");
				}
				yield target.toLowerCase(Locale.ROOT);
			}
			case SLACK -> webhook(target, "hooks.slack.com", "/services/",
					"Use a Slack webhook URL: https://hooks.slack.com/services/…");
			case DISCORD -> webhook(target, "discord.com|discordapp.com", "/api/webhooks/",
					"Use a Discord webhook URL: https://discord.com/api/webhooks/…");
		};
	}

	/** Only HTTPS on the provider's own webhook host and path, and the address must be public. */
	private String webhook(String target, String hosts, String pathPrefix, String hint) {
		URI uri;
		try {
			uri = new URI(target);
		}
		catch (URISyntaxException ex) {
			throw new FieldValidationException("target", hint);
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean ok = "https".equalsIgnoreCase(uri.getScheme()) && host.matches(hosts)
				&& (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null
				&& uri.getPath() != null && uri.getPath().startsWith(pathPrefix)
				&& uri.getPath().length() > pathPrefix.length();
		if (!ok) {
			throw new FieldValidationException("target", hint);
		}
		try {
			ssrfGuard.check(target);
		}
		catch (UnsafeUrlException ex) {
			throw new FieldValidationException("target", ex.getMessage());
		}
		return target;
	}

}
