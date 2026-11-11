package dev.pulse.check;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.pulse.monitor.CheckMethod;

class HttpCheckerTest {

	@RegisterExtension
	static WireMockExtension site = WireMockExtension.newInstance()
		.options(wireMockConfig().dynamicPort().dynamicHttpsPort())
		.build();

	private final HttpChecker checker = TestSites.checker();

	private String url(String path) {
		return "http://site.test:" + site.getPort() + path;
	}

	// ---------- answers ----------

	@Test
	void a2xxIsUpAndSendsOurUserAgent() {
		site.stubFor(get("/ok").willReturn(aResponse().withStatus(200)));

		CheckOutcome outcome = checker.check(TestSites.monitor(url("/ok")));

		assertThat(outcome.success()).isTrue();
		assertThat(outcome.statusCode()).isEqualTo(200);
		assertThat(outcome.errorType()).isEqualTo(ErrorType.NONE);
		assertThat(outcome.latencyMs()).isBetween(0, 2000);
		site.verify(getRequestedFor(urlEqualTo("/ok")).withHeader("User-Agent", equalTo("PulseMonitor/1.0")));
	}

	@Test
	void a503IsAStatusFailure() {
		site.stubFor(get("/down").willReturn(aResponse().withStatus(503)));

		CheckOutcome outcome = checker.check(TestSites.monitor(url("/down")));

		assertThat(outcome.success()).isFalse();
		assertThat(outcome.statusCode()).isEqualTo(503);
		assertThat(outcome.errorType()).isEqualTo(ErrorType.STATUS);
		assertThat(outcome.errorMessage()).isEqualTo("Expected a 2xx status, got 503.");
	}

	@Test
	void anExpectedStatusMustMatchExactly() {
		site.stubFor(get("/gone").willReturn(aResponse().withStatus(404)));
		site.stubFor(get("/empty").willReturn(aResponse().withStatus(204)));

		assertThat(checker.check(TestSites.monitor(url("/gone"), 2000, 404)).success()).isTrue();
		CheckOutcome wrong = checker.check(TestSites.monitor(url("/empty"), 2000, 200));
		assertThat(wrong.success()).isFalse();
		assertThat(wrong.errorMessage()).isEqualTo("Expected status 200, got 204.");
	}

	@Test
	void headMonitorsSendHead() {
		site.stubFor(head(urlEqualTo("/h")).willReturn(aResponse().withStatus(200)));

		CheckOutcome outcome = checker.check(new ClaimedMonitor(UUID.randomUUID(), url("/h"), CheckMethod.HEAD,
				2000, null, null));

		assertThat(outcome.success()).isTrue();
		site.verify(headRequestedFor(urlEqualTo("/h")));
	}

	// ---------- time ----------

	@Test
	void aSlowAnswerIsATimeout() {
		site.stubFor(get("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(2500)));

		CheckOutcome outcome = checker.check(TestSites.monitor(url("/slow"), 1000, null));

		assertThat(outcome.errorType()).isEqualTo(ErrorType.TIMEOUT);
		assertThat(outcome.latencyMs()).isBetween(900, 2400);
	}

	@Test
	void theBodyIsNeverDownloaded() {
		// Headers arrive at once, but the body trickles in over 6 seconds.
		site.stubFor(get("/big").willReturn(aResponse().withStatus(200).withBody("x".repeat(100_000))
			.withChunkedDribbleDelay(20, 6000)));

		long start = System.nanoTime();
		CheckOutcome outcome = checker.check(TestSites.monitor(url("/big"), 10_000, null));
		long tookMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(outcome.success()).isTrue();
		assertThat(tookMs).isLessThan(3000);
	}

	// ---------- redirects ----------

	@Test
	void redirectsAreFollowedToTheFinalAnswer() {
		site.stubFor(get("/a").willReturn(aResponse().withStatus(301).withHeader("Location", "/b")));
		site.stubFor(get("/b").willReturn(aResponse().withStatus(302).withHeader("Location", url("/c"))));
		site.stubFor(get("/c").willReturn(aResponse().withStatus(200)));

		CheckOutcome outcome = checker.check(TestSites.monitor(url("/a")));

		assertThat(outcome.success()).isTrue();
		assertThat(outcome.statusCode()).isEqualTo(200);
	}

	@Test
	void moreThanFiveRedirectsFails() {
		site.stubFor(get("/loop").willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")));

		CheckOutcome outcome = checker.check(TestSites.monitor(url("/loop")));

		assertThat(outcome.errorType()).isEqualTo(ErrorType.STATUS);
		assertThat(outcome.errorMessage()).isEqualTo("More than 5 redirects.");
		site.verify(6, getRequestedFor(urlEqualTo("/loop")));
	}

	@Test
	void anExpected3xxIsNotFollowed() {
		site.stubFor(get("/moved").willReturn(aResponse().withStatus(301).withHeader("Location", "/new")));

		CheckOutcome outcome = checker.check(TestSites.monitor(url("/moved"), 2000, 301));

		assertThat(outcome.success()).isTrue();
		site.verify(0, getRequestedFor(urlEqualTo("/new")));
	}

	@Test
	void aRedirectIntoAPrivateNetworkIsBlocked() {
		for (String target : new String[] { "http://internal.test/", "http://169.254.169.254/latest/meta-data/",
				"http://127.0.0.1:8080/", "ftp://site.test/" }) {
			// 127.0.0.1 itself is allowed in these tests only through the name site.test; port 8080 there is
			// closed, so it fails as CONNECTION, not BLOCKED. Skip that case for the BLOCKED assertion.
			if (target.startsWith("http://127.")) {
				continue;
			}
			site.stubFor(get("/r").willReturn(aResponse().withStatus(302).withHeader("Location", target)));

			CheckOutcome outcome = checker.check(TestSites.monitor(url("/r")));

			assertThat(outcome.errorType()).as(target).isEqualTo(ErrorType.BLOCKED);
			assertThat(outcome.statusCode()).as(target).isEqualTo(302);
		}
	}

	@Test
	void aNameThatNowPointsInsideIsBlockedAtCheckTime() {
		// rebind.test passed the guard when it was saved; now DNS says 10.0.0.5.
		CheckOutcome outcome = checker.check(TestSites.monitor("http://rebind.test:" + site.getPort() + "/"));

		assertThat(outcome.errorType()).isEqualTo(ErrorType.BLOCKED);
		assertThat(outcome.errorMessage()).contains("private or reserved");
	}

	// ---------- network errors ----------

	@Test
	void anUnknownHostIsADnsFailure() {
		CheckOutcome outcome = checker.check(TestSites.monitor("http://nowhere.test/"));

		assertThat(outcome.errorType()).isEqualTo(ErrorType.DNS);
		assertThat(outcome.statusCode()).isNull();
	}

	@Test
	void aClosedPortIsAConnectionFailure() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		CheckOutcome outcome = checker.check(TestSites.monitor("http://site.test:" + closedPort + "/"));

		assertThat(outcome.errorType()).isEqualTo(ErrorType.CONNECTION);
	}

	@Test
	void anUntrustedCertificateIsATlsFailure() {
		site.stubFor(get("/secure").willReturn(aResponse().withStatus(200)));

		CheckOutcome outcome = checker.check(
				TestSites.monitor("https://site.test:" + site.getHttpsPort() + "/secure"));

		assertThat(outcome.errorType()).isEqualTo(ErrorType.TLS);
		assertThat(outcome.errorMessage()).startsWith("HTTPS failed:");
	}

	@Test
	void longErrorMessagesAreCut() {
		CheckOutcome outcome = CheckOutcome.failure(java.time.Instant.now(), null, 1, ErrorType.CONNECTION,
				"x".repeat(2000));

		assertThat(outcome.errorMessage()).hasSize(CheckOutcome.MAX_MESSAGE);
	}

}
