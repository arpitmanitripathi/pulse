package dev.pulse.statuspage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import dev.pulse.IntegrationTest;
import dev.pulse.common.ApiException;
import dev.pulse.statuspage.StatusPageRequests.CreateStatusPageRequest;
import dev.pulse.support.TestUsers;
import dev.pulse.support.TestUsers.TestUser;

@IntegrationTest
class StatusPageApiIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestUsers testUsers;

	@Autowired
	StatusPageService service;

	TestUser alice;
	TestUser bob;
	UUID api;
	UUID web;
	UUID bobs;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE users CASCADE");
		alice = testUsers.create("alice@example.com");
		bob = testUsers.create("bob@example.com");
		api = monitor(alice, "API");
		web = monitor(alice, "Website");
		bobs = monitor(bob, "Bob's");
	}

	@Test
	void createReadUpdateDelete() throws Exception {
		String id = JsonPath.read(create(alice, """
				{"slug":"acme","title":"Acme status","monitorIds":["%s","%s"]}""".formatted(web, api))
			.andExpect(status().isCreated())
			.andExpect(header().exists("Location"))
			.andExpect(jsonPath("$.slug").value("acme"))
			.andExpect(jsonPath("$.monitorIds[0]").value(web.toString()))
			.andExpect(jsonPath("$.monitorIds[1]").value(api.toString()))
			.andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(get("/api/status-pages").header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].title").value("Acme status"));

		mvc.perform(patch("/api/status-pages/" + id).header("Authorization", alice.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"slug":"acme-2","title":"  New title  ","monitorIds":["%s"]}""".formatted(api)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.slug").value("acme-2"))
			.andExpect(jsonPath("$.title").value("New title"))
			.andExpect(jsonPath("$.monitorIds.length()").value(1));
		mvc.perform(patch("/api/status-pages/" + id).header("Authorization", alice.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Only the title\"}"))
			.andExpect(jsonPath("$.slug").value("acme-2"))
			.andExpect(jsonPath("$.monitorIds[0]").value(api.toString()));

		mvc.perform(delete("/api/status-pages/" + id).header("Authorization", alice.bearer()))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/status-pages/" + id).header("Authorization", alice.bearer()))
			.andExpect(status().isNotFound());
	}

	@Test
	void aDeletedMonitorDisappearsFromThePage() throws Exception {
		String id = JsonPath.read(create(alice, """
				{"slug":"acme","title":"Acme","monitorIds":["%s","%s"]}""".formatted(api, web))
			.andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(delete("/api/monitors/" + api).header("Authorization", alice.bearer()));

		mvc.perform(get("/api/status-pages/" + id).header("Authorization", alice.bearer()))
			.andExpect(jsonPath("$.monitorIds.length()").value(1))
			.andExpect(jsonPath("$.monitorIds[0]").value(web.toString()));
	}

	@Test
	void invalidInputIs400() throws Exception {
		create(alice, "{\"slug\":\"-bad-\",\"title\":\"x\",\"monitorIds\":[]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.slug").value(StatusPageRequests.SLUG));
		create(alice, "{\"slug\":\"UPPER\",\"title\":\"x\",\"monitorIds\":[]}")
			.andExpect(jsonPath("$.errors.slug").exists());
		create(alice, "{\"slug\":\"ab\",\"title\":\"x\",\"monitorIds\":[]}")
			.andExpect(jsonPath("$.errors.slug").exists());
		create(alice, "{\"slug\":\"fine\",\"title\":\" \",\"monitorIds\":[]}")
			.andExpect(jsonPath("$.errors.title").value("Title is required."));
		create(alice, "{\"slug\":\"fine\",\"title\":\"x\"}")
			.andExpect(jsonPath("$.errors.monitorIds").exists());
		create(alice, "{\"slug\":\"fine\",\"title\":\"x\",\"monitorIds\":[\"%s\",\"%s\"]}".formatted(api, api))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.monitorIds").value("Each monitor can be on the page only once."));
		assertThat(count()).isZero();
	}

	@Test
	void someoneElsesMonitorCannotBeShownAndLooksLikeAMissingOne() throws Exception {
		String missing = create(alice, "{\"slug\":\"aa1\",\"title\":\"x\",\"monitorIds\":[\"%s\"]}"
			.formatted(UUID.randomUUID())).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		String foreign = create(alice, "{\"slug\":\"aa2\",\"title\":\"x\",\"monitorIds\":[\"%s\"]}".formatted(bobs))
			.andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();

		assertThat(foreign).isEqualTo(missing).contains("One or more monitors were not found.");
		assertThat(count()).isZero();
	}

	@Test
	void slugsAreUniqueAcrossAllUsers() throws Exception {
		create(alice, "{\"slug\":\"acme\",\"title\":\"x\",\"monitorIds\":[]}").andExpect(status().isCreated());
		create(bob, "{\"slug\":\"acme\",\"title\":\"x\",\"monitorIds\":[]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("That address is already taken. Choose another."));
		String bobsPage = JsonPath.read(create(bob, "{\"slug\":\"bob\",\"title\":\"x\",\"monitorIds\":[]}")
			.andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(patch("/api/status-pages/" + bobsPage).header("Authorization", bob.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"acme\"}"))
			.andExpect(status().isConflict());
	}

	@Test
	void anotherUsersPageIs404() throws Exception {
		String id = JsonPath.read(create(alice, "{\"slug\":\"acme\",\"title\":\"x\",\"monitorIds\":[]}")
			.andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(get("/api/status-pages/" + id).header("Authorization", bob.bearer()))
			.andExpect(status().isNotFound());
		mvc.perform(patch("/api/status-pages/" + id).header("Authorization", bob.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"hacked\"}"))
			.andExpect(status().isNotFound());
		mvc.perform(delete("/api/status-pages/" + id).header("Authorization", bob.bearer()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/status-pages").header("Authorization", bob.bearer()))
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void atMostThreePagesEvenWithParallelRequests() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(6);
		List<Callable<Boolean>> calls = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String slug = "page-" + i;
			calls.add(() -> {
				try {
					service.create(alice.id(), new CreateStatusPageRequest(slug, "x", List.of()));
					return true;
				}
				catch (ApiException ex) {
					return false;
				}
			});
		}
		int created = 0;
		for (Future<Boolean> f : pool.invokeAll(calls)) {
			created += f.get() ? 1 : 0;
		}
		pool.shutdown();

		assertThat(created).isEqualTo(StatusPageService.MAX_PAGES_PER_USER);
		assertThat(count()).isEqualTo(3);
		create(alice, "{\"slug\":\"one-more\",\"title\":\"x\",\"monitorIds\":[]}")
			.andExpect(status().isConflict());
	}

	// ---------- helpers ----------

	private ResultActions create(TestUser user, String body) throws Exception {
		return mvc.perform(post("/api/status-pages").header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private UUID monitor(TestUser owner, String name) {
		return jdbc.queryForObject("""
				INSERT INTO monitors (user_id, name, url, interval_s) VALUES (?, ?, 'https://example.com/', 60)
				RETURNING id""", UUID.class, owner.id(), name);
	}

	private int count() {
		return jdbc.queryForObject("SELECT count(*) FROM status_pages", Integer.class);
	}

}
