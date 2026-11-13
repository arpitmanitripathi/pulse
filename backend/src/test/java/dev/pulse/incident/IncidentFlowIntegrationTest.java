package dev.pulse.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import dev.pulse.IntegrationTest;
import dev.pulse.alert.AlertChannelType;
import dev.pulse.alert.AlertOutboxDispatcher;
import dev.pulse.check.CheckOutcome;
import dev.pulse.check.CheckResultProcessor;
import dev.pulse.check.ClaimedMonitor;
import dev.pulse.check.ErrorType;
import dev.pulse.monitor.CheckMethod;
import dev.pulse.support.RecordingAlertSenders;
import dev.pulse.support.TestUsers;
import dev.pulse.support.TestUsers.TestUser;

@IntegrationTest
class IncidentFlowIntegrationTest {

	static final String URL = "https://example.com/health";

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	@Autowired
	CheckResultProcessor processor;

	@Autowired
	MockMvc mvc;

	TestUser alice;
	UUID monitorId;
	Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		alice = testUsers.create("alice@example.com");
		monitorId = jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s) VALUES (?, 'API', ?, 60) RETURNING id
				""", UUID.class, alice.id(), URL);
		addChannel("Email", "EMAIL", "alice@example.com");
		addChannel("Team Slack", "SLACK", "https://hooks.slack.com/services/T1/B1/xyz");
	}

	@Test
	void threeFailuresOpenOneIncidentAndQueueOneDownAlertPerChannel() {
		fail();
		fail();
		assertThat(state()).isEqualTo("UNKNOWN");
		assertThat(count("incidents")).isZero();

		fail();

		assertThat(state()).isEqualTo("DOWN");
		assertThat(count("incidents")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT first_error_type FROM incidents", String.class)).isEqualTo("TIMEOUT");
		assertThat(outboxKinds()).containsExactly("DOWN", "DOWN");

		fail();
		fail();
		assertThat(count("incidents")).isEqualTo(1); // still the same incident
		assertThat(outboxKinds()).hasSize(2);        // no extra alerts
		assertThat(count("check_results")).isEqualTo(5);
	}

	@Test
	void twoSuccessesResolveItAndQueueRecovered() {
		fail();
		fail();
		fail();

		succeed();
		assertThat(state()).isEqualTo("DOWN"); // one success is not enough
		succeed();

		assertThat(state()).isEqualTo("UP");
		assertThat(jdbc.queryForObject("SELECT resolved_at IS NOT NULL FROM incidents", Boolean.class)).isTrue();
		assertThat(resolution()).isEqualTo("RECOVERED");
		assertThat(outboxKinds()).containsExactly("DOWN", "DOWN", "RECOVERED", "RECOVERED");
	}

	@Test
	void aChannelAddedDuringAnOutageIsNotToldItRecovered() {
		fail();
		fail();
		fail();
		addChannel("Discord", "DISCORD", "https://discord.com/api/webhooks/1/abc");
		succeed();
		succeed();

		Integer discordAlerts = jdbc.queryForObject("""
				SELECT count(*) FROM alert_outbox o JOIN alert_channels c ON c.id = o.channel_id
				WHERE c.type = 'DISCORD'""", Integer.class);
		assertThat(discordAlerts).isZero();
		assertThat(outboxKinds()).containsExactly("DOWN", "DOWN", "RECOVERED", "RECOVERED");
	}

	@Test
	void aFirstSuccessMeansUpWithoutAnyAlert() {
		succeed();

		assertThat(state()).isEqualTo("UP");
		assertThat(count("incidents")).isZero();
		assertThat(count("alert_outbox")).isZero();
	}

	@Test
	void aResultForAnOldUrlIsDropped() {
		processor.record(new ClaimedMonitor(monitorId, "https://old.example.com/", CheckMethod.GET, 5000, null, t),
				failure());

		assertThat(count("check_results")).isZero();
		assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM monitors", Integer.class)).isZero();
	}

	@Test
	void theDatabaseRefusesASecondDownAlertForTheSameIncidentAndChannel() {
		fail();
		fail();
		fail();

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO alert_outbox (incident_id, channel_id, kind)
				SELECT incident_id, channel_id, 'DOWN' FROM alert_outbox LIMIT 1"""))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void changingTheUrlOrResumingClosesTheOpenIncidentWithoutRecovered() throws Exception {
		fail();
		fail();
		fail();

		mvc.perform(patch("/api/monitors/" + monitorId).header("Authorization", alice.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://status.example.org/\"}"))
			.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents WHERE resolved_at IS NULL", Integer.class))
			.isZero();
		assertThat(resolution()).isEqualTo("MONITOR_CHANGED");
		assertThat(outboxKinds()).containsExactly("DOWN", "DOWN");

		// Same for resume after a pause.
		jdbc.update("UPDATE monitors SET url = ?", URL);
		fail();
		fail();
		fail();
		mvc.perform(post("/api/monitors/" + monitorId + "/pause").header("Authorization", alice.bearer()))
			.andExpect(status().isOk());
		mvc.perform(post("/api/monitors/" + monitorId + "/resume").header("Authorization", alice.bearer()))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents WHERE resolved_at IS NULL", Integer.class))
			.isZero();
	}

	@Test
	void pausingClosesTheOpenIncidentThereAndThenWithoutRecovered() throws Exception {
		fail();
		fail();
		fail();
		Instant beforePause = Instant.now().minusSeconds(1);

		mvc.perform(post("/api/monitors/" + monitorId + "/pause").header("Authorization", alice.bearer()))
			.andExpect(status().isOk());

		assertThat(resolution()).isEqualTo("MONITOR_CHANGED");
		Instant closedAt = jdbc.queryForObject("SELECT resolved_at FROM incidents", OffsetDateTime.class).toInstant();
		assertThat(closedAt).as("closed when paused, not later").isAfter(beforePause);
		assertThat(outboxKinds()).containsExactly("DOWN", "DOWN"); // nobody is told it recovered
		String incidentId = jdbc.queryForObject("SELECT id::text FROM incidents", String.class);
		mvc.perform(get("/api/incidents/" + incidentId).header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.resolution").value("MONITOR_CHANGED"));
	}

	@Test
	void aResultThatArrivesAfterThePauseIsDropped() {
		fail();
		fail();
		jdbc.update("UPDATE monitors SET active = false WHERE id = ?", monitorId); // paused while a check ran

		fail(); // would have been the third failure

		assertThat(count("check_results")).isEqualTo(2);
		assertThat(count("incidents")).as("no incident on a paused monitor").isZero();
		assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM monitors", Integer.class)).isEqualTo(2);
	}

	@Test
	void deletingAChannelKeepsTheRecordOfAlertsAlreadySentToIt() throws Exception {
		fail();
		fail();
		fail();
		// Email delivered its DOWN alert; Slack gave up after 6 attempts.
		jdbc.update("UPDATE alert_outbox SET sent_at = now(), attempts = 1 WHERE channel_id = ?", channel("Email"));
		jdbc.update("UPDATE alert_outbox SET attempts = 6, last_error = 'Slack answered HTTP 404.' WHERE channel_id = ?",
				channel("Team Slack"));
		String incidentId = jdbc.queryForObject("SELECT id::text FROM incidents", String.class);

		for (String name : List.of("Email", "Team Slack")) {
			mvc.perform(delete("/api/alert-channels/" + channel(name)).header("Authorization", alice.bearer()))
				.andExpect(status().isNoContent());
		}

		mvc.perform(get("/api/incidents/" + incidentId).header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.alerts.length()").value(2)) // the history survives the channels
			.andExpect(jsonPath("$.alerts[0].channelName").value("Email"))
			.andExpect(jsonPath("$.alerts[0].type").value("EMAIL"))
			.andExpect(jsonPath("$.alerts[0].sentAt").isNotEmpty())
			.andExpect(jsonPath("$.alerts[0].channelDeleted").value(true))
			.andExpect(jsonPath("$.alerts[1].channelName").value("Team Slack"))
			.andExpect(jsonPath("$.alerts[1].attempts").value(6))
			.andExpect(jsonPath("$.alerts[1].lastError").value("Slack answered HTTP 404."));
	}

	@Test
	void aDeletedChannelLosesItsUnsentAlertsAndIsNeverToldItRecovered(@Autowired RecordingAlertSenders senders,
			@Autowired org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named) throws Exception {
		fail();
		fail();
		fail();
		jdbc.update("UPDATE alert_outbox SET sent_at = now(), attempts = 1 WHERE channel_id = ?", channel("Team Slack"));
		// Email's DOWN alert is still waiting to be sent when the channel is deleted.
		mvc.perform(delete("/api/alert-channels/" + channel("Email")).header("Authorization", alice.bearer()))
			.andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_outbox WHERE sent_at IS NULL", Integer.class))
			.as("the deleted channel's unsent alert is dropped").isZero();

		succeed();
		succeed();
		assertThat(outboxKinds()).as("RECOVERED only for the channel that still exists").containsExactly("DOWN", "RECOVERED");

		senders.reset();
		// The fake checks above run ahead of real time, so the dispatcher's clock does too.
		try (AlertOutboxDispatcher dispatcher = new AlertOutboxDispatcher(named, senders,
				java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(10)))) {
			dispatcher.dispatchDue();
		}
		assertThat(senders.sent()).extracting(RecordingAlertSenders.Sent::type).containsExactly(AlertChannelType.SLACK);
	}

	@Test
	void aChannelDeletedAfterItsDownAlertWasSentGetsNoRecoveredAlert() throws Exception {
		fail();
		fail();
		fail();
		jdbc.update("UPDATE alert_outbox SET sent_at = now(), attempts = 1"); // both DOWN alerts delivered
		mvc.perform(delete("/api/alert-channels/" + channel("Email")).header("Authorization", alice.bearer()))
			.andExpect(status().isNoContent());

		succeed();
		succeed();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_outbox WHERE kind = 'RECOVERED' AND channel_id IS NULL",
				Integer.class)).as("no RECOVERED alert addressed to a deleted channel").isZero();
		assertThat(outboxKinds()).containsExactly("DOWN", "DOWN", "RECOVERED"); // Email's DOWN kept as history
		String incidentId = jdbc.queryForObject("SELECT id::text FROM incidents", String.class);
		mvc.perform(get("/api/incidents/" + incidentId).header("Authorization", alice.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.alerts.length()").value(3));
	}

	// ---------- helpers ----------

	private UUID channel(String name) {
		return jdbc.queryForObject("SELECT id FROM alert_channels WHERE name = ?", UUID.class, name);
	}

	private String resolution() {
		return jdbc.queryForObject("SELECT resolution FROM incidents ORDER BY opened_at LIMIT 1", String.class);
	}

	private void fail() {
		processor.record(claimed(), failure());
	}

	private void succeed() {
		t = t.plusSeconds(60);
		processor.record(claimed(), CheckOutcome.success(t, 200, 80));
	}

	private CheckOutcome failure() {
		t = t.plusSeconds(60);
		return new CheckOutcome(t, false, null, 5000, ErrorType.TIMEOUT, "No answer within 5000 ms.");
	}

	private ClaimedMonitor claimed() {
		return new ClaimedMonitor(monitorId, URL, CheckMethod.GET, 5000, null, t);
	}

	private void addChannel(String name, String type, String target) {
		jdbc.update("INSERT INTO alert_channels (user_id, type, target, name) VALUES (?, ?, ?, ?)", alice.id(), type,
				target, name);
	}

	private String state() {
		return jdbc.queryForObject("SELECT state FROM monitors WHERE id = ?", String.class, monitorId);
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private List<String> outboxKinds() {
		return jdbc.queryForList("SELECT kind FROM alert_outbox ORDER BY kind, id", String.class);
	}

}
