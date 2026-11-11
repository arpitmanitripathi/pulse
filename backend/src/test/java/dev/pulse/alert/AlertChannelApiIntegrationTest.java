package dev.pulse.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import dev.pulse.IntegrationTest;
import dev.pulse.support.RecordingAlertSenders;
import dev.pulse.support.TestUsers;
import dev.pulse.support.TestUsers.TestUser;

@IntegrationTest
class AlertChannelApiIntegrationTest {

	static final String SLACK = "https://hooks.slack.com/services/T000/B000/abcdefgh1234";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	@Autowired
	RecordingAlertSenders senders;

	@Autowired
	StringRedisTemplate redis;

	TestUser alice;
	TestUser bob;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		redis.execute((RedisCallback<Void>) c -> {
			c.serverCommands().flushAll();
			return null;
		});
		senders.reset();
		alice = testUsers.create("alice@example.com");
		bob = testUsers.create("bob@example.com");
	}

	@Test
	void createsEachTypeAndNeverShowsAWebhookUrl() throws Exception {
		create("EMAIL", "On-call", " Ops@Example.com ").andExpect(status().isCreated())
			.andExpect(jsonPath("$.target").value("ops@example.com"));
		create("SLACK", "Team Slack", SLACK).andExpect(status().isCreated())
			.andExpect(jsonPath("$.target").value("https://hooks.slack.com/…1234"));
		create("DISCORD", "Discord", "https://discord.com/api/webhooks/123/secret-token-wxyz")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.target").value("https://discord.com/…wxyz"));

		String list = mvc.perform(as(alice, get("/api/alert-channels"))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andReturn().getResponse().getContentAsString();
		assertThat(list).doesNotContain("abcdefgh", "secret-token");
	}

	@Test
	void webhooksMustBeOnTheProvidersOwnHostOverHttps() throws Exception {
		for (String bad : new String[] {
				"http://hooks.slack.com/services/T/B/x",          // not https
				"https://evil.example.com/services/T/B/x",        // wrong host
				"https://hooks.slack.com.evil.com/services/T/B/x",// lookalike host
				"https://hooks.slack.com/other/T/B/x",            // wrong path
				"https://hooks.slack.com/services/",              // nothing after the prefix
				"https://user@hooks.slack.com/services/T/B/x",    // user info
				"https://hooks.slack.com:8443/services/T/B/x" }) {// odd port
			create("SLACK", "x", bad).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.target").value(org.hamcrest.Matchers.startsWith("Use a Slack webhook URL")));
		}
		create("DISCORD", "x", "https://discord.com/api/other/1/x").andExpect(status().isBadRequest());
		create("EMAIL", "x", "not-an-email").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.target").value("Enter a valid email address."));
		create(null, "", "").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.type").exists())
			.andExpect(jsonPath("$.errors.name").exists())
			.andExpect(jsonPath("$.errors.target").exists());
	}

	@Test
	void fiveChannelsAtMost() throws Exception {
		for (int i = 0; i < AlertChannelService.MAX_CHANNELS_PER_USER; i++) {
			create("EMAIL", "c" + i, "ops" + i + "@example.com").andExpect(status().isCreated());
		}
		create("EMAIL", "one more", "x@example.com").andExpect(status().isConflict());
	}

	@Test
	void anotherUsersChannelLooksLikeItDoesNotExist() throws Exception {
		String id = id(create("EMAIL", "Bob", "bob@example.com", bob));

		mvc.perform(as(alice, delete("/api/alert-channels/" + id))).andExpect(status().isNotFound());
		mvc.perform(as(alice, post("/api/alert-channels/" + id + "/test"))).andExpect(status().isNotFound());
		mvc.perform(as(bob, delete("/api/alert-channels/" + id))).andExpect(status().isNoContent());
		mvc.perform(get("/api/alert-channels")).andExpect(status().isUnauthorized());
	}

	@Test
	void theTestAlertIsSentRightAway() throws Exception {
		String id = id(create("SLACK", "Team Slack", SLACK));

		mvc.perform(as(alice, post("/api/alert-channels/" + id + "/test")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.delivered").value(true));

		assertThat(senders.sent()).hasSize(1);
		assertThat(senders.sent().get(0).target()).isEqualTo(SLACK);
		assertThat(senders.sent().get(0).message().text()).contains("test alert", "Team Slack");
	}

	@Test
	void aFailedTestAlertSaysWhy() throws Exception {
		String id = id(create("SLACK", "Team Slack", SLACK));
		senders.failNext(1);

		mvc.perform(as(alice, post("/api/alert-channels/" + id + "/test")))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.detail").value("The test alert was not delivered. Slack answered HTTP 500."));
	}

	@Test
	void testAlertsAreLimitedToFiveAMinute() throws Exception {
		String id = id(create("EMAIL", "Me", "alice@example.com"));
		for (int i = 0; i < 5; i++) {
			mvc.perform(as(alice, post("/api/alert-channels/" + id + "/test"))).andExpect(status().isOk());
		}
		mvc.perform(as(alice, post("/api/alert-channels/" + id + "/test")))
			.andExpect(status().isTooManyRequests());
	}

	// ---------- helpers ----------

	private org.springframework.test.web.servlet.ResultActions create(String type, String name, String target)
			throws Exception {
		return create(type, name, target, alice);
	}

	private org.springframework.test.web.servlet.ResultActions create(String type, String name, String target,
			TestUser user) throws Exception {
		String typeJson = type == null ? "null" : "\"" + type + "\"";
		return mvc.perform(as(user, post("/api/alert-channels")).content("""
				{"type":%s,"name":"%s","target":"%s"}""".formatted(typeJson, name, target)));
	}

	private static String id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private MockHttpServletRequestBuilder as(TestUser user, MockHttpServletRequestBuilder request) {
		return request.header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON);
	}

}
