package dev.pulse.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class IncidentApiIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	TestUser alice;
	TestUser bob;
	UUID api;
	UUID web;
	UUID openIncident;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		alice = testUsers.create("alice@example.com");
		bob = testUsers.create("bob@example.com");
		api = monitor(alice, "API");
		web = monitor(alice, "Website");
		Instant now = Instant.now();
		incident(api, now.minusSeconds(7200), now.minusSeconds(6000));
		openIncident = incident(api, now.minusSeconds(600), null);
		incident(web, now.minusSeconds(300), null);
		incident(monitor(bob, "Bob's"), now.minusSeconds(100), null);
		UUID channel = jdbc.queryForObject("""
				INSERT INTO alert_channels (user_id, type, target, name) VALUES (?, 'EMAIL', 'a@example.com', 'Email')
				RETURNING id""", UUID.class, alice.id());
		jdbc.update("""
				INSERT INTO alert_outbox (incident_id, channel_id, kind, attempts, last_error)
				VALUES (?, ?, 'DOWN', 2, 'The email could not be sent: timeout')""", openIncident, channel);
	}

	@Test
	void listShowsYourIncidentsNewestFirst() throws Exception {
		mvc.perform(get("/api/incidents").header("Authorization", alice.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].monitorName").value("Website"))
			.andExpect(jsonPath("$[0].summaryStatus").value("PENDING"))
			.andExpect(jsonPath("$[2].resolvedAt").isNotEmpty())
			.andExpect(jsonPath("$[0].alerts").doesNotExist());
	}

	@Test
	void listFiltersByMonitorAndStatus() throws Exception {
		mvc.perform(get("/api/incidents?monitorId=" + api + "&status=open").header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(openIncident.toString()));
		mvc.perform(get("/api/incidents?status=resolved").header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/incidents?status=maybe").header("Authorization", alice.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.status").value("Status must be open or resolved."));
	}

	@Test
	void oneIncidentShowsHowEachAlertWent() throws Exception {
		mvc.perform(get("/api/incidents/" + openIncident).header("Authorization", alice.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.monitorName").value("API"))
			.andExpect(jsonPath("$.firstErrorType").value("TIMEOUT"))
			.andExpect(jsonPath("$.alerts[0].channelName").value("Email"))
			.andExpect(jsonPath("$.alerts[0].kind").value("DOWN"))
			.andExpect(jsonPath("$.alerts[0].attempts").value(2))
			.andExpect(jsonPath("$.alerts[0].sentAt").doesNotExist())
			.andExpect(jsonPath("$.alerts[0].lastError").value("The email could not be sent: timeout"));
	}

	@Test
	void anotherUsersIncidentLooksLikeItDoesNotExist() throws Exception {
		mvc.perform(get("/api/incidents/" + openIncident).header("Authorization", bob.bearer()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/incidents").header("Authorization", bob.bearer()))
			.andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized());
	}

	private UUID monitor(TestUser owner, String name) {
		return jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s) VALUES (?, ?, 'https://example.com/', 60)
				RETURNING id""", UUID.class, owner.id(), name);
	}

	private UUID incident(UUID monitorId, Instant opened, Instant resolved) {
		return jdbc.queryForObject("""
				INSERT INTO incidents (monitor_id, opened_at, resolved_at, first_error_type) VALUES (?, ?, ?, 'TIMEOUT')
				RETURNING id""", UUID.class, monitorId, OffsetDateTime.ofInstant(opened, ZoneOffset.UTC),
				resolved == null ? null : OffsetDateTime.ofInstant(resolved, ZoneOffset.UTC));
	}

}
