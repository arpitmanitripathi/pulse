package dev.pulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.pulse.IntegrationTest;
import dev.pulse.common.ApiException;
import dev.pulse.support.RecordingAccountMailer;
import dev.pulse.support.TestUsers;
import jakarta.servlet.http.Cookie;

@IntegrationTest
class PasswordResetIntegrationTest {

	static final String EMAIL = "alice@example.com";
	static final String NEW_PASSWORD = "a-brand-new-passphrase";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	TestUsers testUsers;

	@Autowired
	RecordingAccountMailer mailer;

	@Autowired
	PasswordResetService service;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		redis.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll(); // rate-limit counters
			return null;
		});
		mailer.reset();
		testUsers.create(EMAIL); // password "test-password-123"
	}

	@Test
	void anUnknownEmailGetsTheSameAnswerAndNothingHappens() throws Exception {
		forgot("nobody@example.com").andExpect(status().isNoContent());

		Thread.sleep(300); // give a background send the chance to happen (it must not)
		assertThat(mailer.sent()).isEmpty();
		assertThat(tokenRows()).isZero();
	}

	@Test
	void aKnownEmailGetsAOneTimeLinkAndOnlyItsHashIsStored() throws Exception {
		forgot("  ALICE@example.com ").andExpect(status().isNoContent());

		RecordingAccountMailer.Sent sent = mailer.awaitSent(1).get(0);
		assertThat(sent.email()).isEqualTo(EMAIL);
		assertThat(sent.link()).startsWith("http://localhost:5173/reset-password?token=");
		String token = token(sent);
		assertThat(token).hasSizeGreaterThanOrEqualTo(43); // 256 bits, base64url
		assertThat(jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens", String.class))
			.isEqualTo(AuthService.sha256(token))
			.isNotEqualTo(token);
	}

	@Test
	void theLinkSetsTheNewPasswordAndEndsEverySession() throws Exception {
		String oldRefresh = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(credentials("test-password-123"))).andReturn().getResponse().getCookie("refresh_token").getValue();
		forgot(EMAIL);

		reset(token(mailer.awaitSent(1).get(0)), NEW_PASSWORD).andExpect(status().isNoContent());

		mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", oldRefresh)))
			.andExpect(status().isUnauthorized()); // the session from before the reset is over
		login("test-password-123").andExpect(status().isUnauthorized());
		login(NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void aLinkWorksOnceAndUsingOneEndsTheOthers() throws Exception {
		forgot(EMAIL);
		forgot(EMAIL);
		List<RecordingAccountMailer.Sent> sent = mailer.awaitSent(2);

		reset(token(sent.get(0)), NEW_PASSWORD).andExpect(status().isNoContent());

		reset(token(sent.get(0)), "another-new-passphrase").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(PasswordResetService.INVALID_LINK));
		reset(token(sent.get(1)), "another-new-passphrase").andExpect(status().isBadRequest());
		login(NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void anExpiredOrMadeUpLinkIsRefused() throws Exception {
		forgot(EMAIL);
		String token = token(mailer.awaitSent(1).get(0));
		jdbc.update("UPDATE password_reset_tokens SET expires_at = now() - interval '1 second'");

		reset(token, NEW_PASSWORD).andExpect(status().isBadRequest());
		reset("made-up-token", NEW_PASSWORD).andExpect(status().isBadRequest());
		login("test-password-123").andExpect(status().isOk()); // nothing changed
	}

	@Test
	void aCommonPasswordIsRefusedWithoutUsingUpTheLink() throws Exception {
		forgot(EMAIL);
		String token = token(mailer.awaitSent(1).get(0));

		reset(token, "Password123").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.password").value(AuthService.TOO_COMMON));
		reset(token, "short").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.password").value("Password must be 10 to 72 characters."));

		reset(token, NEW_PASSWORD).andExpect(status().isNoContent()); // the link still works
	}

	@Test
	void atMostThreeEmailsPerAddressPerHour() throws Exception {
		for (int i = 0; i < 5; i++) {
			forgot(EMAIL).andExpect(status().isNoContent()); // the answer never changes
		}

		Thread.sleep(300);
		assertThat(mailer.awaitSent(3)).hasSize(3);
		assertThat(tokenRows()).isEqualTo(3);
	}

	@Test
	void twoRequestsRacingWithOneLinkSucceedOnlyOnce() throws Exception {
		forgot(EMAIL);
		String token = token(mailer.awaitSent(1).get(0));
		ExecutorService pool = Executors.newFixedThreadPool(4);
		List<Callable<Boolean>> calls = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			String password = "racing-passphrase-" + i;
			calls.add(() -> {
				try {
					service.reset(token, password);
					return true;
				}
				catch (ApiException ex) {
					return false;
				}
			});
		}
		int succeeded = 0;
		for (Future<Boolean> f : pool.invokeAll(calls)) {
			succeeded += f.get() ? 1 : 0;
		}
		pool.shutdown();

		assertThat(succeeded).isEqualTo(1);
	}

	@Test
	void badRequestsAreRefusedAndForgotPasswordIsRateLimitedPerIp() throws Exception {
		forgot("not-an-email").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.email").value("Enter a valid email address."));
		mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"" + NEW_PASSWORD + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.token").exists());

		int limited = 0;
		for (int i = 0; i < 12; i++) {
			limited += forgot("x" + i + "@example.com").andReturn().getResponse().getStatus() == 429 ? 1 : 0;
		}
		assertThat(limited).as("shares the login allowance of 10 per minute per IP").isPositive();
	}

	// ---------- helpers ----------

	private ResultActions forgot(String email) throws Exception {
		return mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\"}"));
	}

	private ResultActions reset(String token, String password) throws Exception {
		return mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"));
	}

	private ResultActions login(String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(password)));
	}

	private static String credentials(String password) {
		return "{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}";
	}

	private static String token(RecordingAccountMailer.Sent sent) {
		return sent.link().substring(sent.link().indexOf("token=") + "token=".length());
	}

	private int tokenRows() {
		return jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Integer.class);
	}

}
