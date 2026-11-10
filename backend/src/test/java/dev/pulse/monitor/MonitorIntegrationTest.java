package dev.pulse.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import dev.pulse.IntegrationTest;
import dev.pulse.common.ApiException;
import dev.pulse.monitor.MonitorRequests.CreateMonitorRequest;
import dev.pulse.support.TestUsers;
import dev.pulse.support.TestUsers.TestUser;

@IntegrationTest
class MonitorIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	@Autowired
	MonitorService monitorService;

	TestUser alice;
	TestUser bob;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		alice = testUsers.create("alice@example.com");
		bob = testUsers.create("bob@example.com");
	}

	// ---------- create ----------

	@Test
	void createUsesDefaultsAndStartsUnknownAndDueNow() throws Exception {
		Instant before = Instant.now();

		String id = JsonPath.read(mvc.perform(as(alice, post("/api/monitors"))
				.content("""
						{"name":" API health ","url":"https://example.com/health","intervalSeconds":60}"""))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/monitors/")))
			.andExpect(jsonPath("$.name").value("API health"))
			.andExpect(jsonPath("$.method").value("GET"))
			.andExpect(jsonPath("$.timeoutMs").value(5000))
			.andExpect(jsonPath("$.expectedStatus").doesNotExist())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.state").value("UNKNOWN"))
			.andReturn().getResponse().getContentAsString(), "$.id");

		Instant nextCheck = nextCheckAt(id);
		assertThat(nextCheck).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
	}

	@Test
	void createNamesEveryInvalidField() throws Exception {
		mvc.perform(as(alice, post("/api/monitors")).content("""
				{"name":"","url":"https://example.com","method":"POST","intervalSeconds":45,
				 "timeoutMs":500,"expectedStatus":700}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.name").value("Name is required."))
			.andExpect(jsonPath("$.errors.method").value("Method must be GET or HEAD."))
			.andExpect(jsonPath("$.errors.intervalSeconds").value("Interval must be 30, 60, 120 or 300 seconds."))
			.andExpect(jsonPath("$.errors.timeoutMs").value("Timeout must be between 1000 and 10000 ms."))
			.andExpect(jsonPath("$.errors.expectedStatus").value("Expected status must be between 100 and 599."));
	}

	@Test
	void createRejectsUrlsThatPointInside() throws Exception {
		for (String url : List.of("http://127.0.0.1:8080/", "http://internal.test/", "http://169.254.169.254/")) {
			mvc.perform(as(alice, post("/api/monitors")).content(body("Sneaky", url)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.url").value(org.hamcrest.Matchers.containsString("private")));
		}
		assertThat(count(alice)).isZero();
	}

	@Test
	void createStopsAtTwentyMonitors() throws Exception {
		for (int i = 0; i < MonitorService.MAX_MONITORS_PER_USER; i++) {
			mvc.perform(as(alice, post("/api/monitors")).content(body("m" + i, "https://example.com/" + i)))
				.andExpect(status().isCreated());
		}

		mvc.perform(as(alice, post("/api/monitors")).content(body("one too many", "https://example.com/x")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("You can have at most 20 monitors. Delete one to add another."));
		// Bob has his own allowance.
		mvc.perform(as(bob, post("/api/monitors")).content(body("bob's", "https://example.com/")))
			.andExpect(status().isCreated());
	}

	@Test
	void parallelCreatesCannotGoPastTheLimit() throws Exception {
		for (int i = 0; i < 15; i++) {
			monitorService.create(alice.id(), request("m" + i));
		}
		ExecutorService pool = Executors.newFixedThreadPool(10);
		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			String name = "parallel" + i;
			attempts.add(() -> {
				try {
					monitorService.create(alice.id(), request(name));
					return true;
				}
				catch (ApiException ex) {
					return false;
				}
			});
		}
		int created = 0;
		for (Future<Boolean> f : pool.invokeAll(attempts)) {
			created += f.get() ? 1 : 0;
		}
		pool.shutdown();

		assertThat(created).isEqualTo(5);
		assertThat(count(alice)).isEqualTo(20);
	}

	// ---------- read ----------

	@Test
	void listShowsOnlyYourOwnMonitorsOldestFirst() throws Exception {
		create(alice, "first");
		create(alice, "second");
		create(bob, "bob's");

		mvc.perform(as(alice, get("/api/monitors")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].name").value("first"))
			.andExpect(jsonPath("$[1].name").value("second"));
	}

	@Test
	void anotherUsersMonitorLooksLikeItDoesNotExist() throws Exception {
		String bobsId = create(bob, "bob's");
		String missingId = UUID.randomUUID().toString();

		for (String id : List.of(bobsId, missingId)) {
			mvc.perform(as(alice, get("/api/monitors/" + id))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Monitor not found."));
			mvc.perform(as(alice, patch("/api/monitors/" + id)).content("{\"name\":\"mine now\"}"))
				.andExpect(status().isNotFound());
			mvc.perform(as(alice, post("/api/monitors/" + id + "/pause"))).andExpect(status().isNotFound());
			mvc.perform(as(alice, post("/api/monitors/" + id + "/resume"))).andExpect(status().isNotFound());
			mvc.perform(as(alice, delete("/api/monitors/" + id))).andExpect(status().isNotFound());
		}
		mvc.perform(as(bob, get("/api/monitors/" + bobsId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("bob's"));
	}

	@Test
	void everyMonitorEndpointNeedsALogin() throws Exception {
		mvc.perform(get("/api/monitors")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
				.content(body("x", "https://example.com"))).andExpect(status().isUnauthorized());
	}

	// ---------- update ----------

	@Test
	void patchChangesOnlyTheFieldsSent() throws Exception {
		String id = JsonPath.read(mvc.perform(as(alice, post("/api/monitors")).content("""
				{"name":"api","url":"https://example.com/","method":"HEAD","intervalSeconds":120,
				 "timeoutMs":3000,"expectedStatus":204}""")).andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(as(alice, patch("/api/monitors/" + id)).content("{\"name\":\"renamed\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("renamed"))
			.andExpect(jsonPath("$.method").value("HEAD"))
			.andExpect(jsonPath("$.intervalSeconds").value(120))
			.andExpect(jsonPath("$.timeoutMs").value(3000))
			.andExpect(jsonPath("$.expectedStatus").value(204));

		// Sending null for expectedStatus clears it (back to "any 2xx").
		mvc.perform(as(alice, patch("/api/monitors/" + id)).content("{\"expectedStatus\":null,\"intervalSeconds\":30}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expectedStatus").doesNotExist())
			.andExpect(jsonPath("$.intervalSeconds").value(30))
			.andExpect(jsonPath("$.name").value("renamed"));
	}

	@Test
	void patchValidatesFieldsAndUrlSafety() throws Exception {
		String id = create(alice, "api");

		mvc.perform(as(alice, patch("/api/monitors/" + id)).content("{\"name\":\"   \",\"intervalSeconds\":10}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.name").exists())
			.andExpect(jsonPath("$.errors.intervalSeconds").exists());
		mvc.perform(as(alice, patch("/api/monitors/" + id)).content("{\"url\":\"http://10.0.0.1/\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.url").exists());
	}

	@Test
	void newUrlResetsStateAndChecksRightAway() throws Exception {
		String id = create(alice, "api");
		markDownAndCheckLater(id);

		mvc.perform(as(alice, patch("/api/monitors/" + id)).content("{\"url\":\"https://status.example.org/\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.url").value("https://status.example.org/"))
			.andExpect(jsonPath("$.state").value("UNKNOWN"));

		assertThat(nextCheckAt(id)).isBefore(Instant.now().plusSeconds(1));
		assertThat(failures(id)).isZero();
	}

	@Test
	void sameUrlOrOtherChangesKeepTheState() throws Exception {
		String id = create(alice, "api");
		markDownAndCheckLater(id);

		mvc.perform(as(alice, patch("/api/monitors/" + id)).content("{\"url\":\"https://example.com/\",\"name\":\"x\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("DOWN"));
	}

	// ---------- pause / resume / delete ----------

	@Test
	void pauseStopsChecksAndResumeStartsFresh() throws Exception {
		String id = create(alice, "api");
		markDownAndCheckLater(id);

		mvc.perform(as(alice, post("/api/monitors/" + id + "/pause")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false))
			.andExpect(jsonPath("$.state").value("DOWN"));

		mvc.perform(as(alice, post("/api/monitors/" + id + "/resume")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.state").value("UNKNOWN"));
		assertThat(nextCheckAt(id)).isBefore(Instant.now().plusSeconds(1));

		// Resuming a monitor that is already running changes nothing.
		markDownAndCheckLater(id);
		mvc.perform(as(alice, post("/api/monitors/" + id + "/resume")))
			.andExpect(jsonPath("$.state").value("DOWN"));
	}

	@Test
	void deleteRemovesTheMonitor() throws Exception {
		String id = create(alice, "api");

		mvc.perform(as(alice, delete("/api/monitors/" + id))).andExpect(status().isNoContent());
		mvc.perform(as(alice, get("/api/monitors/" + id))).andExpect(status().isNotFound());
		assertThat(count(alice)).isZero();
	}

	// ---------- helpers ----------

	private MockHttpServletRequestBuilder as(TestUser user, MockHttpServletRequestBuilder request) {
		return request.header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON);
	}

	private String create(TestUser user, String name) throws Exception {
		return JsonPath.read(mvc.perform(as(user, post("/api/monitors")).content(body(name, "https://example.com/")))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private static String body(String name, String url) {
		return """
				{"name":"%s","url":"%s","intervalSeconds":60}""".formatted(name, url);
	}

	private static CreateMonitorRequest request(String name) {
		return new CreateMonitorRequest(name, "https://example.com/", null, 60, null, null);
	}

	private void markDownAndCheckLater(String id) {
		jdbc.update("UPDATE monitors SET state = 'DOWN', consecutive_failures = 3, "
				+ "next_check_at = now() + interval '1 hour' WHERE id = ?::uuid", id);
	}

	private Instant nextCheckAt(String id) {
		return jdbc.queryForObject("SELECT next_check_at FROM monitors WHERE id = ?::uuid", Timestamp.class, id)
			.toInstant();
	}

	private int failures(String id) {
		return jdbc.queryForObject("SELECT consecutive_failures FROM monitors WHERE id = ?::uuid", Integer.class, id);
	}

	private int count(TestUser user) {
		return jdbc.queryForObject("SELECT count(*) FROM monitors WHERE user_id = ?", Integer.class, user.id());
	}

}
