package dev.pulse.check;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import dev.pulse.IntegrationTest;
import dev.pulse.support.TestUsers;
import dev.pulse.support.TestUsers.TestUser;

@IntegrationTest
class CheckApiIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	TestUser alice;
	TestUser bob;
	UUID monitorId;
	final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		alice = testUsers.create("alice@example.com");
		bob = testUsers.create("bob@example.com");
		monitorId = jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s) VALUES (?, 'api', 'https://example.com/', 60)
				RETURNING id""", UUID.class, alice.id());
		insertCheck(now.minusSeconds(60), true, 200, "NONE", null);
		insertCheck(now.minusSeconds(120), false, 503, "STATUS", "Expected a 2xx status, got 503.");
		insertCheck(now.minusSeconds(180), false, null, "TIMEOUT", "No answer within 5000 ms.");
		insertCheck(now.minusSeconds(25 * 3600), true, 200, "NONE", null); // older than 24 h
	}

	@Test
	void checksFromTheLast24HoursComeNewestFirst() throws Exception {
		mvc.perform(get("/api/monitors/" + monitorId + "/checks").header("Authorization", alice.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].success").value(true))
			.andExpect(jsonPath("$.items[1].statusCode").value(503))
			.andExpect(jsonPath("$.items[1].errorType").value("STATUS"))
			.andExpect(jsonPath("$.items[2].errorType").value("TIMEOUT"))
			.andExpect(jsonPath("$.items[2].statusCode").doesNotExist())
			.andExpect(jsonPath("$.hasMore").value(false));
	}

	@Test
	void pagesSayWhetherMoreExist() throws Exception {
		mvc.perform(get("/api/monitors/" + monitorId + "/checks?size=2").header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.hasMore").value(true));
		mvc.perform(get("/api/monitors/" + monitorId + "/checks?size=2&page=1")
				.header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].errorType").value("TIMEOUT"))
			.andExpect(jsonPath("$.hasMore").value(false));
	}

	@Test
	void badPagingIsRejected() throws Exception {
		mvc.perform(get("/api/monitors/" + monitorId + "/checks?size=201").header("Authorization", alice.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.size").value("Size must be 1 to 200."));
		mvc.perform(get("/api/monitors/" + monitorId + "/checks?page=-1").header("Authorization", alice.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.page").exists());
	}

	@Test
	void onlyTheOwnerCanSeeTheChecks() throws Exception {
		mvc.perform(get("/api/monitors/" + monitorId + "/checks").header("Authorization", bob.bearer()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/monitors/" + monitorId + "/checks"))
			.andExpect(status().isUnauthorized());
	}

	private void insertCheck(Instant at, boolean success, Integer status, String errorType, String message) {
		jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, status_code, latency_ms, error_type, error_message)
				VALUES (?, ?, ?, ?, 42, ?, ?)""",
				monitorId, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), success, status, errorType, message);
	}

}
