package dev.pulse.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.pulse.common.ApiException;
import dev.pulse.common.FieldValidationException;

@Service
public class AuthService {

	/** BCrypt only reads the first 72 bytes of a password. */
	static final int MAX_PASSWORD_BYTES = 72;

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);
	private static final String INVALID_LOGIN = "Invalid email or password.";
	private static final String INVALID_SESSION = "Your session has ended. Please log in again.";
	static final String TOO_COMMON = "This password is too common and easy to guess. Choose another one.";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final AuthProperties properties;
	private final Clock clock;
	private final CommonPasswords commonPasswords;

	/** Compared against when the email is unknown, so both cases take the same time. */
	private final String dummyHash;

	public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
			JwtService jwtService, AuthProperties properties, Clock clock, CommonPasswords commonPasswords) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.properties = properties;
		this.clock = clock;
		this.commonPasswords = commonPasswords;
		this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
	}

	@Transactional
	public User register(String rawEmail, String password) {
		String email = normalizeEmail(rawEmail);
		checkNewPassword(password);
		if (users.existsByEmail(email)) {
			throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists.");
		}
		User user = users.save(new User(email, passwordEncoder.encode(password), clock.instant()));
		log.info("auth.registered user_id={}", user.getId());
		return user;
	}

	/**
	 * Rules for a new password, beyond the 10-character minimum checked on the request:
	 * at most 72 bytes (BCrypt reads no more) and not a common password.
	 */
	void checkNewPassword(String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at most 72 bytes.");
		}
		if (commonPasswords.contains(password)) {
			throw new FieldValidationException("password", TOO_COMMON);
		}
	}

	@Transactional
	public Session login(String rawEmail, String password) {
		Optional<User> user = users.findByEmail(normalizeEmail(rawEmail));
		boolean tooLong = password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
		// Always run one BCrypt comparison, so an unknown email is not faster to reject.
		String hash = user.map(User::getPasswordHash).orElse(dummyHash);
		boolean matches = !tooLong && passwordEncoder.matches(password, hash);
		if (user.isEmpty() || !matches) {
			log.info("auth.login_failed");
			throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN);
		}
		log.info("auth.login user_id={}", user.get().getId());
		return startSession(user.get());
	}

	/**
	 * Swaps a refresh token for a new access token and a new refresh token.
	 * The old refresh token stops working (rotation). If someone presents a
	 * token that was already rotated, it was probably stolen, so every session
	 * of that user is ended.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public Session refresh(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_SESSION);
		}
		Instant now = clock.instant();
		RefreshToken token = refreshTokens.findByTokenHash(sha256(rawToken))
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, INVALID_SESSION));
		if (token.isRevoked()) {
			int ended = refreshTokens.revokeAllForUser(token.getUserId(), now);
			log.warn("auth.refresh_token_reused user_id={} sessions_ended={}", token.getUserId(), ended);
			throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_SESSION);
		}
		if (token.isExpired(now)) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_SESSION);
		}
		token.revoke(now);
		User user = users.findById(token.getUserId())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, INVALID_SESSION));
		return startSession(user);
	}

	@Transactional
	public void logout(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return;
		}
		refreshTokens.findByTokenHash(sha256(rawToken)).ifPresent(token -> token.revoke(clock.instant()));
	}

	private Session startSession(User user) {
		Instant now = clock.instant();
		String rawRefresh = newRandomToken();
		Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
		refreshTokens.save(new RefreshToken(user.getId(), sha256(rawRefresh), now, refreshExpiresAt));
		JwtService.IssuedToken access = jwtService.issueAccessToken(user.getId());
		return new Session(access.value(), access.expiresAt(), rawRefresh, refreshExpiresAt);
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	/** 256 random bits, URL-safe. */
	static String newRandomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available in the JDK", ex);
		}
	}

	/** What a successful login or refresh hands back. */
	public record Session(String accessToken, Instant accessExpiresAt, String refreshToken,
			Instant refreshExpiresAt) {
	}

}
