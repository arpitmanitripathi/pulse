package dev.pulse.statuspage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.pulse.IntegrationTest;
import dev.pulse.stats.StatsRepository;
import dev.pulse.stats.StatsService;
import dev.pulse.support.TestUsers;
import dev.pulse.support.TestUsers.TestUser;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class PublicStatusIntegrationTest {

	/** Each test calls from its own IP, so the per-IP rate limit of one test can't affect another. */
	static final AtomicInteger NEXT_IP = new AtomicInteger(1);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	StatusPageRepository pages;

	@Autowired
	StatsService stats;

	@Autowired
	StatsRepository statsRepository;

	@Autowired
	JsonMapper json;

	TestUser alice;
	UUID api;
	UUID paused;
	UUID pageId;
	String ip;
	final Instant now = Instant.now();

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		redis.delete(PublicStatusService.key("acme"));
		ip = "10.9.0." + NEXT_IP.getAndIncrement();
		alice = testUsers.create("alice@example.com");
		api = jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s, state)
				VALUES (?, 'Public API', 'https://secret-internal.example.com/health', 60, 'DOWN') RETURNING id""",
				UUID.class, alice.id());
		paused = jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s, state, active)
				VALUES (?, 'Old site', 'https://old.example.com/', 60, 'UP', false) RETURNING id""",
				UUID.class, alice.id());
		pageId = jdbc.queryForObject("""
				INSERT INTO status_pages (user_id, slug, title) VALUES (?, 'acme', 'Acme status') RETURNING id""",
				UUID.class, alice.id());
		jdbc.update("INSERT INTO status_page_monitors (status_page_id, monitor_id, position) VALUES (?, ?, 0), (?, ?, 1)",
				pageId, api, pageId, paused);

		check(now.minus(Duration.ofMinutes(10)), true, 120);
		check(now.minus(Duration.ofMinutes(20)), false, 5000);
		rollup(now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofDays(3)), 60, 0, 90);
		rollup(now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofDays(20)), 60, 30, 90);
		rollup(now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofDays(60)), 60, 60, null);
		incident(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(40)).plusSeconds(600)); // too old
		incident(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(2)).plusSeconds(300));
		incident(now.minus(Duration.ofMinutes(15)), null);
	}

	@Test
	void showsStateUptimeBarsLatencyAndIncidentsWithoutLogin() throws Exception {
		LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
		mvc.perform(publicGet("acme"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "max-age=30, public"))
			.andExpect(jsonPath("$.title").value("Acme status"))
			.andExpect(jsonPath("$.monitors.length()").value(2))
			.andExpect(jsonPath("$.monitors[0].name").value("Public API"))
			.andExpect(jsonPath("$.monitors[0].state").value("DOWN"))
			.andExpect(jsonPath("$.monitors[0].uptime24h").value(50.0))  // 1 of 2
			.andExpect(jsonPath("$.monitors[0].uptime7d").value(98.38))  // 61 of 62
			.andExpect(jsonPath("$.monitors[0].uptime30d").value(74.59)) // 91 of 122 = 74.590…
			.andExpect(jsonPath("$.monitors[0].days.length()").value(90))
			.andExpect(jsonPath("$.monitors[0].days[89].date").value(today.toString()))
			.andExpect(jsonPath("$.monitors[0].days[89].uptime").exists())
			.andExpect(jsonPath("$.monitors[0].days[29].uptime").value(0.0)) // 60 days ago: all failed
			.andExpect(jsonPath("$.monitors[0].days[0].uptime").doesNotExist())
			.andExpect(jsonPath("$.monitors[0].latency24h.length()").value(24))
			.andExpect(jsonPath("$.monitors[1].name").value("Old site"))
			.andExpect(jsonPath("$.monitors[1].state").value("PAUSED"))
			.andExpect(jsonPath("$.monitors[1].uptime24h").doesNotExist())
			.andExpect(jsonPath("$.incidents.length()").value(2))
			.andExpect(jsonPath("$.incidents[0].monitorName").value("Public API"))
			.andExpect(jsonPath("$.incidents[0].resolvedAt").doesNotExist())
			.andExpect(jsonPath("$.incidents[1].resolvedAt").exists());
	}

	@Test
	void neverRevealsUrlsIdsErrorsOrAiText() throws Exception {
		String body = mvc.perform(publicGet("acme")).andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("example.com", api.toString(), paused.toString(), pageId.toString(),
				alice.id().toString(), "secret AI text", "connection refused", "STATUS", "\"id\"", "\"url\"");
	}

	@Test
	void unknownOrMalformedSlugIs404() throws Exception {
		mvc.perform(publicGet("nope")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Status page not found."));
		mvc.perform(publicGet("NOT a slug")).andExpect(status().isNotFound());
	}

	@Test
	void cachedForThirtySecondsButAnEditShowsAtOnce() throws Exception {
		mvc.perform(publicGet("acme")).andExpect(jsonPath("$.monitors[0].uptime24h").value(50.0));
		Long ttl = redis.getExpire(PublicStatusService.key("acme"));
		assertThat(ttl).isBetween(1L, 30L);

		check(now.minus(Duration.ofMinutes(1)), true, 100); // new data: not visible while cached
		mvc.perform(publicGet("acme")).andExpect(jsonPath("$.monitors[0].uptime24h").value(50.0));

		mvc.perform(patch("/api/status-pages/" + pageId).header("Authorization", alice.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Acme, renamed\"}"))
			.andExpect(status().isOk());
		mvc.perform(publicGet("acme"))
			.andExpect(jsonPath("$.title").value("Acme, renamed"))
			.andExpect(jsonPath("$.monitors[0].uptime24h").value(66.66)); // rebuilt: 2 of 3
	}

	@Test
	void changingTheSlugFreesTheOldAddressAtOnce() throws Exception {
		mvc.perform(publicGet("acme")).andExpect(status().isOk());
		mvc.perform(patch("/api/status-pages/" + pageId).header("Authorization", alice.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"acme-new\"}"));

		mvc.perform(publicGet("acme")).andExpect(status().isNotFound());
		mvc.perform(publicGet("acme-new")).andExpect(status().isOk());
		redis.delete(PublicStatusService.key("acme-new"));
	}

	@Test
	void sixtyRequestsPerMinutePerIp() throws Exception {
		for (int i = 0; i < 60; i++) {
			mvc.perform(publicGet("acme")).andExpect(status().isOk());
		}
		mvc.perform(publicGet("acme"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));
	}

	@Test
	void withRedisDownThePageIsStillServed() {
		LettuceConnectionFactory down = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
		down.afterPropertiesSet();
		try {
			PublicStatusService noCache = new PublicStatusService(pages, jdbc, stats, statsRepository,
					new StringRedisTemplate(down), json, Clock.systemUTC());

			assertThat(noCache.get("acme").title()).isEqualTo("Acme status");
		}
		finally {
			down.destroy();
		}
	}

	// ---------- helpers ----------

	private MockHttpServletRequestBuilder publicGet(String slug) {
		return get("/api/public/status/" + slug).with(request -> {
			request.setRemoteAddr(ip);
			return request;
		});
	}

	private void check(Instant at, boolean success, int latencyMs) {
		jdbc.update("""
				INSERT INTO check_results (monitor_id, checked_at, success, status_code, latency_ms, error_type, error_message)
				VALUES (?, ?, ?, ?, ?, ?, ?)""", api, utc(at), success, success ? 200 : null, latencyMs,
				success ? "NONE" : "CONNECTION", success ? null : "connection refused");
	}

	private void rollup(Instant hour, int checks, int failures, Integer latency) {
		jdbc.update("""
				INSERT INTO hourly_rollups (monitor_id, hour, checks, failures, latency_p50, latency_p95)
				VALUES (?, ?, ?, ?, ?, ?)""", api, utc(hour), checks, failures, latency, latency);
	}

	private void incident(Instant opened, Instant resolved) {
		jdbc.update("""
				INSERT INTO incidents (monitor_id, opened_at, resolved_at, first_error_type, summary, summary_status)
				VALUES (?, ?, ?, 'STATUS', 'secret AI text', 'DONE')""", api, utc(opened),
				resolved == null ? null : utc(resolved));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
