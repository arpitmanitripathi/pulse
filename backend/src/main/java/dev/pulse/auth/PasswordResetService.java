package dev.pulse.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.pulse.common.ApiException;
import dev.pulse.security.RateLimiter;
import jakarta.annotation.PreDestroy;

/**
 * Forgot password, by an emailed one-time link (docs/DESIGN.md, "Password reset").
 *
 * <ul>
 * <li>Asking for a link answers the same way whether or not the email has an account, and the
 * email is sent in the background, so the answer can't reveal who is registered.</li>
 * <li>The token is 256 random bits; only its SHA-256 hash is stored. Valid 30 min, single use.</li>
 * <li>At most 3 emails per address per hour, so the form can't be used to flood an inbox.</li>
 * <li>Using a link sets the new password and ends every session of that user.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

	public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
	static final int EMAILS_PER_HOUR = 3;
	static final String INVALID_LINK = "This reset link is invalid or has expired. Ask for a new one.";

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final AuthService authService;
	private final PasswordEncoder passwordEncoder;
	private final AccountMailer mailer;
	private final RateLimiter rateLimiter;
	private final AuthProperties properties;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

	public PasswordResetService(UserRepository users, RefreshTokenRepository refreshTokens, AuthService authService,
			PasswordEncoder passwordEncoder, AccountMailer mailer, RateLimiter rateLimiter, AuthProperties properties,
			JdbcTemplate jdbc, Clock clock) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.authService = authService;
		this.passwordEncoder = passwordEncoder;
		this.mailer = mailer;
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Emails a reset link if the account exists. Returns the same way either way. */
	@Transactional
	public void requestReset(String rawEmail) {
		String email = AuthService.normalizeEmail(rawEmail);
		Optional<User> user = users.findByEmail(email);
		if (user.isEmpty()) {
			log.info("auth.reset_requested known=false");
			return;
		}
		if (!rateLimiter.tryAcquire("reset-email", AuthService.sha256(email), EMAILS_PER_HOUR, Duration.ofHours(1))
			.allowed()) {
			log.info("auth.reset_requested user_id={} limited=true", user.get().getId());
			return;
		}
		Instant now = clock.instant();
		String token = AuthService.newRandomToken();
		jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)",
				user.get().getId(), AuthService.sha256(token), utc(now.plus(TOKEN_TTL)), utc(now));
		String link = properties.appUrl().replaceAll("/+$", "") + "/reset-password?token=" + token;
		UUID userId = user.get().getId();
		afterCommit(() -> background.submit(() -> {
			try {
				mailer.sendPasswordReset(email, link);
				log.info("auth.reset_email_sent user_id={}", userId);
			}
			catch (RuntimeException ex) {
				log.warn("auth.reset_email_failed user_id={} error={}", userId, ex.getClass().getSimpleName());
			}
		}));
		log.info("auth.reset_requested user_id={}", userId);
	}

	/**
	 * Sets a new password with a link's token. The password is checked first, so a refused password
	 * doesn't use up the link. The token is then consumed in one statement, so it works only once
	 * even if two requests race.
	 */
	@Transactional
	public void reset(String token, String newPassword) {
		authService.checkNewPassword(newPassword);
		Instant now = clock.instant();
		List<UUID> owner = jdbc.queryForList("""
				UPDATE password_reset_tokens SET used_at = ?
				WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?
				RETURNING user_id""", UUID.class, utc(now), AuthService.sha256(token), utc(now));
		if (owner.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_LINK);
		}
		UUID userId = owner.get(0);
		jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(newPassword), userId);
		jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL", utc(now),
				userId); // other links stop working too
		int ended = refreshTokens.revokeAllForUser(userId, now);
		log.info("auth.password_reset user_id={} sessions_ended={}", userId, ended);
	}

	private static void afterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		}
		else {
			action.run();
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	@PreDestroy
	void close() {
		background.close();
	}

}
