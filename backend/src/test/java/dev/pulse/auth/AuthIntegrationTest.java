package dev.pulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import dev.pulse.IntegrationTest;
import jakarta.servlet.http.Cookie;

@IntegrationTest
class AuthIntegrationTest {

	private static final String EMAIL = "arpit@example.com";
	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	AuthProperties authProperties;

	@BeforeEach
	void cleanState() {
		jdbc.execute("TRUNCATE users CASCADE");
		// Reset rate-limit counters so each test starts with a full allowance.
		redis.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}

	// ---------- register ----------

	@Test
	void registerCreatesAccountWithLowercasedEmailAndBcryptHash() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json("  Arpit@Example.COM ", PASSWORD)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(EMAIL))
			.andExpect(jsonPath("$.id").isNotEmpty());

		String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL);
		assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
	}

	@Test
	void registerRejectsAnEmailThatIsAlreadyUsed() throws Exception {
		register();

		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json("ARPIT@example.com", PASSWORD)))
			.andExpect(status().isConflict())
			.andExpect(header().string("Content-Type", "application/problem+json"))
			.andExpect(jsonPath("$.detail").value("An account with this email already exists."));
	}

	@Test
	void registerNamesEveryInvalidField() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json("not-an-email", "short")))
			.andExpect(status().isBadRequest())
			.andExpect(header().string("Content-Type", "application/problem+json"))
			.andExpect(jsonPath("$.errors.email").value("Enter a valid email address."))
			.andExpect(jsonPath("$.errors.password").value("Password must be 10 to 72 characters."));
	}

	@Test
	void registerRejectsPasswordsLongerThan72Bytes() throws Exception {
		String password = "é".repeat(40); // 40 characters, 80 bytes in UTF-8
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json(EMAIL, password)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Password must be at most 72 bytes."));
	}

	// ---------- login ----------

	@Test
	void loginReturnsAccessTokenAndASafeRefreshCookie() throws Exception {
		register();

		MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json(EMAIL, PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn();

		String setCookie = result.getResponse().getHeader("Set-Cookie");
		assertThat(setCookie).startsWith("refresh_token=")
			.contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=604800");
		assertThat(accessToken(result)).isNotBlank();
	}

	@Test
	void loginGivesTheSameAnswerForWrongPasswordAndUnknownEmail() throws Exception {
		register();

		String wrongPassword = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json(EMAIL, "wrong-password-123")))
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		String unknownEmail = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json("nobody@example.com", PASSWORD)))
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();

		assertThat(wrongPassword).isEqualTo(unknownEmail).contains("Invalid email or password.");
	}

	// ---------- access token ----------

	@Test
	void meNeedsAValidAccessToken() throws Exception {
		register();
		String token = accessToken(login());

		mvc.perform(get("/api/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("WWW-Authenticate", "Bearer"))
			.andExpect(jsonPath("$.status").value(401));

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(EMAIL));
	}

	@Test
	void tamperedAndExpiredAccessTokensAreRejected() throws Exception {
		register();
		String token = accessToken(login());
		String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + tampered))
			.andExpect(status().isUnauthorized());

		UUID userId = UUID.fromString(jdbc.queryForObject("SELECT id::text FROM users", String.class));
		Clock anHourAgo = Clock.fixed(Instant.now().minusSeconds(3600), java.time.ZoneOffset.UTC);
		String expired = new JwtService(jwtEncoder, authProperties, anHourAgo).issueAccessToken(userId).value();

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + expired))
			.andExpect(status().isUnauthorized());
	}

	// ---------- refresh ----------

	@Test
	void refreshRotatesTheCookieAndTheNewAccessTokenWorks() throws Exception {
		register();
		String first = refreshCookie(login());

		MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", first)))
			.andExpect(status().isOk())
			.andReturn();

		String second = refreshCookie(refreshed);
		assertThat(second).isNotBlank().isNotEqualTo(first);
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken(refreshed)))
			.andExpect(status().isOk());
	}

	@Test
	void reusingAnOldRefreshTokenEndsEverySessionOfThatUser() throws Exception {
		register();
		String stolen = refreshCookie(login());
		String current = refreshCookie(mvc.perform(post("/api/auth/refresh")
				.cookie(new Cookie("refresh_token", stolen))).andReturn());

		// The old (already rotated) token is presented again: treat as theft.
		mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", stolen)))
			.andExpect(status().isUnauthorized());

		// The legitimate newer token is now dead too.
		mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", current)))
			.andExpect(status().isUnauthorized());
		Integer active = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",
				Integer.class);
		assertThat(active).isZero();
	}

	@Test
	void refreshFailsWithoutACookieAndWithAnExpiredOne() throws Exception {
		mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());

		register();
		String token = refreshCookie(login());
		jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 second'");

		mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", token)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Your session has ended. Please log in again."));
	}

	// ---------- logout ----------

	@Test
	void logoutEndsTheSessionAndClearsTheCookie() throws Exception {
		register();
		String token = refreshCookie(login());

		MvcResult result = mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", token)))
			.andExpect(status().isNoContent())
			.andReturn();
		assertThat(result.getResponse().getHeader("Set-Cookie")).contains("refresh_token=", "Max-Age=0");

		mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", token)))
			.andExpect(status().isUnauthorized());
	}

	// ---------- common passwords ----------

	@Test
	void aCommonPasswordIsRefusedWhateverItsCase() throws Exception {
		for (String common : List.of("password123", "Password123", "QWERTYUIOP", "1234567890")) {
			mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(EMAIL, common)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value(AuthService.TOO_COMMON));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
		register(); // an uncommon password of the same length works
	}

	// ---------- rate limit ----------

	@Test
	void authEndpointsAreRateLimitedPerIp() throws Exception {
		List<Integer> statuses = new ArrayList<>();
		MvcResult limited = null;
		for (int i = 0; i < 25; i++) {
			MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content(json(EMAIL, "wrong-password-123"))).andReturn();
			statuses.add(r.getResponse().getStatus());
			if (r.getResponse().getStatus() == 429 && limited == null) {
				limited = r;
			}
		}

		assertThat(statuses.subList(0, 10)).doesNotContain(429);
		assertThat(statuses).contains(429);
		assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
		assertThat(limited.getResponse().getContentType()).isEqualTo("application/problem+json");
	}

	@Test
	void refreshIsNotRateLimitedBecauseTheAppCallsItOnEveryPageLoad() throws Exception {
		for (int i = 0; i < 25; i++) {
			mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized()); // never 429
		}
		register();
		login(); // the login allowance was not used up by refreshes
	}

	// ---------- helpers ----------

	private void register() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json(EMAIL, PASSWORD)))
			.andExpect(status().isCreated());
	}

	private MvcResult login() throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json(EMAIL, PASSWORD)))
			.andExpect(status().isOk())
			.andReturn();
	}

	private static String accessToken(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
	}

	private static String refreshCookie(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie("refresh_token");
		return cookie == null ? null : cookie.getValue();
	}

	private static String json(String email, String password) {
		return """
				{"email":"%s","password":"%s"}""".formatted(email, password);
	}

}
