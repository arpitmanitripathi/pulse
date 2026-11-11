package dev.pulse.stats;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
class StatsApiIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	TestUser alice;
	TestUser bob;
	UUID api;
	UUID quiet;
	final Instant now = Instant.now();
	final Instant thisHour = now.truncatedTo(ChronoUnit.HOURS);

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		alice = testUsers.create("alice@example.com");
		bob = testUsers.create("bob@example.com");
		api = monitor(alice, "API", 2);
		quiet = monitor(alice, "Never checked", 1);

		// Last 24 h: 4 checks, 1 failed. Successful latencies 100, 200, 400 → exact p50 200, p95 400.
		check(api, now.minus(Duration.ofMinutes(5)), true, 100);
		check(api, now.minus(Duration.ofHours(2)), true, 200);
		check(api, now.minus(Duration.ofHours(3)), true, 400);
		check(api, now.minus(Duration.ofHours(4)), false, 9000);
		// Older than the last 24 hours, stats read rollups (written by the hourly job; inserted by hand here).
		Instant oldCheck = now.minus(Duration.ofHours(25));
		check(api, oldCheck, false, 9000); // outside 24 h; its hour's rollup below
		rollup(api, oldCheck.truncatedTo(ChronoUnit.HOURS), 1, 1, null, null);
		rollup(api, thisHour.minus(Duration.ofDays(2)), 60, 6, 300, 600);
		rollup(api, thisHour.minus(Duration.ofDays(10)), 60, 60, null, null);
	}

	@Test
	void twentyFourHoursIsExactFromRawChecks() throws Exception {
		mvc.perform(get("/api/monitors/" + api + "/stats").header("Authorization", alice.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.window").value("24h"))
			.andExpect(jsonPath("$.checks").value(4))
			.andExpect(jsonPath("$.failures").value(1))
			.andExpect(jsonPath("$.uptime").value(75.0))
			.andExpect(jsonPath("$.latencyP50").value(200))
			.andExpect(jsonPath("$.latencyP95").value(400))
			.andExpect(jsonPath("$.bucket").value("HOUR"))
			.andExpect(jsonPath("$.series.length()").value(24))
			.andExpect(jsonPath("$.series[23].start").value(thisHour.toString()));
	}

	@Test
	void sevenDaysCombinesRawHoursWithRollups() throws Exception {
		// raw 4 checks (1 failed) + rollups 1 (1 failed) and 60 (6 failed) = 65 checks, 8 failed → 87.69 %
		mvc.perform(get("/api/monitors/" + api + "/stats?window=7d").header("Authorization", alice.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.checks").value(65))
			.andExpect(jsonPath("$.failures").value(8))
			.andExpect(jsonPath("$.uptime").value(87.69))
			.andExpect(jsonPath("$.series.length()").value(168))
			.andExpect(jsonPath("$.from").value(thisHour.minus(Duration.ofHours(167)).toString()));
	}

	@Test
	void thirtyDaysHasDailyPointsAndIncludesOlderRollups() throws Exception {
		// 65 checks / 8 failed from above, plus 60 / 60 ten days ago → 125 checks, 68 failed → 45.6 %
		mvc.perform(get("/api/monitors/" + api + "/stats?window=30d").header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.checks").value(125))
			.andExpect(jsonPath("$.failures").value(68))
			.andExpect(jsonPath("$.uptime").value(45.6))
			.andExpect(jsonPath("$.bucket").value("DAY"))
			.andExpect(jsonPath("$.series.length()").value(30));
	}

	@Test
	void aMonitorWithoutChecksHasNullsNotZeros() throws Exception {
		mvc.perform(get("/api/monitors/" + quiet + "/stats?window=7d").header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.checks").value(0))
			.andExpect(jsonPath("$.uptime").doesNotExist())
			.andExpect(jsonPath("$.latencyP50").doesNotExist());
	}

	@Test
	void badWindowIs400AndSomeoneElsesMonitorIs404() throws Exception {
		mvc.perform(get("/api/monitors/" + api + "/stats?window=1y").header("Authorization", alice.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.window").value("Window must be 24h, 7d or 30d."));
		mvc.perform(get("/api/monitors/" + api + "/stats").header("Authorization", bob.bearer()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/monitors/" + api + "/stats")).andExpect(status().isUnauthorized());
	}

	@Test
	void everyMonitorResponseCarriesItsTwentyFourHourUptime() throws Exception {
		mvc.perform(get("/api/monitors").header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$[0].name").value("API"))
			.andExpect(jsonPath("$[0].uptime24h").value(75.0))
			.andExpect(jsonPath("$[1].uptime24h").doesNotExist());
		mvc.perform(get("/api/monitors/" + api).header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.uptime24h").value(75.0));
	}

	// ---------- helpers ----------

	private UUID monitor(TestUser owner, String name, int minutesAgo) {
		return jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s, created_at) VALUES (?, ?, 'https://example.com/', 60, ?)
				RETURNING id""", UUID.class, owner.id(), name, utc(now.minus(Duration.ofMinutes(minutesAgo))));
	}

	private void check(UUID monitor, Instant at, boolean success, int latencyMs) {
		jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, status_code, latency_ms, error_type)
				VALUES (?, ?, ?, ?, ?, ?)""", monitor, utc(at), success, success ? 200 : null, latencyMs,
				success ? "NONE" : "TIMEOUT");
	}

	private void rollup(UUID monitor, Instant hour, int checks, int failures, Integer p50, Integer p95) {
		jdbc.update("""
				INSERT INTO hourly_rollups (monitor_id, hour, checks, failures, latency_p50, latency_p95)
				VALUES (?, ?, ?, ?, ?, ?)""", monitor, utc(hour), checks, failures, p50, p95);
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
