package dev.pulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * App-level checks: boots against real Postgres and Redis, Flyway builds the
 * v1 schema, health is public and UP, and the API docs are served.
 */
@IntegrationTest
class PulseApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MockMvc mvc;

	@Value("${local.server.port}")
	int port;

	@Test
	void flywayCreatesEveryTableInThePlan() {
		List<String> tables = jdbc.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
				""", String.class);

		assertThat(tables).containsExactlyInAnyOrder(
				"users", "refresh_tokens", "monitors", "check_results", "incidents",
				"alert_channels", "alert_outbox", "hourly_rollups", "status_pages", "status_page_monitors");
	}

	@Test
	void alertOutboxAllowsOnlyOneAlertPerIncidentChannelAndKind() {
		Integer count = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.table_constraints
				WHERE table_name = 'alert_outbox' AND constraint_name = 'alert_outbox_once'
				  AND constraint_type = 'UNIQUE'
				""", Integer.class);

		assertThat(count).isEqualTo(1);
	}

	@Test
	void healthEndpointIsPublicAndReportsUp() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	@Test
	void apiDocsArePublicAndListTheAuthEndpoints() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/auth/login']").exists())
			.andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
	}

}
