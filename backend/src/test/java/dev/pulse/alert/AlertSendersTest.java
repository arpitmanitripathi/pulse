package dev.pulse.alert;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.pulse.security.HostResolver;
import dev.pulse.security.SsrfGuard;
import tools.jackson.databind.json.JsonMapper;

/** The real senders, against a fake webhook server (WireMock) and a mocked mail server. */
class AlertSendersTest {

	@RegisterExtension
	static WireMockExtension hooks = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	static final Instant OPENED = Instant.parse("2026-11-11T14:03:00Z");
	static final AlertMessage DOWN = new AlertMessage(AlertKind.DOWN, "API", "https://api.example.com/health",
			"Expected a 2xx status, got 503.", OPENED, null, "Team");
	static final AlertMessage RECOVERED = new AlertMessage(AlertKind.RECOVERED, "API",
			"https://api.example.com/health", null, OPENED, OPENED.plus(Duration.ofMinutes(65)), "Team");

	// "hooks.test" points at WireMock on 127.0.0.1; "inside.test" at a private address.
	static final HostResolver DNS = host -> switch (host) {
		case "hooks.test" -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
		case "inside.test" -> new InetAddress[] { InetAddress.getByName("10.0.0.9") };
		default -> throw new UnknownHostException(host);
	};
	final WebhookClient webhooks = new WebhookClient(
			new SsrfGuard(DNS, a -> SsrfGuard.isPublic(a) || a.isLoopbackAddress()));
	final JsonMapper json = JsonMapper.builder().build();

	String hook(String path) {
		return "http://hooks.test:" + hooks.getPort() + path;
	}

	@Test
	void slackGetsTheTextAsJson() throws Exception {
		hooks.stubFor(post("/slack").willReturn(aResponse().withStatus(200).withBody("ok")));

		new SlackSender(webhooks, json).send(hook("/slack"), DOWN);

		hooks.verify(postRequestedFor(urlEqualTo("/slack"))
			.withHeader("Content-Type", equalTo("application/json; charset=UTF-8"))
			.withHeader("User-Agent", equalTo("PulseMonitor/1.0"))
			.withRequestBody(equalToJson("{\"text\":\"" + DOWN.text().replace("\n", "\\n") + "\"}")));
	}

	@Test
	void discordGetsContentCutTo2000Characters() throws Exception {
		hooks.stubFor(post("/discord").willReturn(aResponse().withStatus(204)));
		AlertMessage huge = new AlertMessage(AlertKind.DOWN, "API", "https://x/", "e".repeat(3000), OPENED, null, "T");

		new DiscordSender(webhooks, json).send(hook("/discord"), huge);

		String body = hooks.getAllServeEvents().get(0).getRequest().getBodyAsString();
		String content = json.readTree(body).get("content").asString();
		assertThat(content).hasSize(2000).endsWith("…");
	}

	@Test
	void aWebhookErrorIsReportedWithTheStatus() {
		hooks.stubFor(post("/gone").willReturn(aResponse().withStatus(404)));

		assertThatThrownBy(() -> new SlackSender(webhooks, json).send(hook("/gone"), DOWN))
			.isInstanceOf(AlertDeliveryException.class)
			.hasMessage("Slack answered HTTP 404.");
	}

	@Test
	void aWebhookThatNowResolvesInsideIsRefused() {
		assertThatThrownBy(() -> new SlackSender(webhooks, json).send("http://inside.test/x", DOWN))
			.isInstanceOf(AlertDeliveryException.class)
			.hasMessageContaining("private address");
	}

	@Test
	void emailHasTheTitleAsSubjectAndTheBody() throws Exception {
		JavaMailSender mail = mock(JavaMailSender.class);

		new EmailSender(mail, new AlertProperties("Pulse <alerts@pulse.local>")).send("ops@example.com", RECOVERED);

		ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mail).send(sent.capture());
		assertThat(sent.getValue().getTo()).containsExactly("ops@example.com");
		assertThat(sent.getValue().getFrom()).isEqualTo("Pulse <alerts@pulse.local>");
		assertThat(sent.getValue().getSubject()).isEqualTo("✅ API is back up");
		assertThat(sent.getValue().getText()).contains("back up after 1 h 5 min",
				"Down from 2026-11-11 14:03:00 UTC to 2026-11-11 15:08:00 UTC");
	}

	@Test
	void anEmailServerErrorBecomesADeliveryError() {
		JavaMailSender mail = mock(JavaMailSender.class);
		doThrow(new MailSendException("connection refused")).when(mail).send(any(SimpleMailMessage.class));

		assertThatThrownBy(() -> new EmailSender(mail, new AlertProperties("a@b.c")).send("ops@example.com", DOWN))
			.isInstanceOf(AlertDeliveryException.class)
			.hasMessageStartingWith("The email could not be sent");
	}

	@Test
	void theRegistryPicksTheSenderByType() throws Exception {
		JavaMailSender mail = mock(JavaMailSender.class);
		DefaultAlertSenders senders = new DefaultAlertSenders(
				List.of(new EmailSender(mail, new AlertProperties("a@b.c"))));

		senders.send(AlertChannelType.EMAIL, "ops@example.com", DOWN);
		verify(mail).send(any(SimpleMailMessage.class));
		assertThatThrownBy(() -> senders.send(AlertChannelType.SLACK, "x", DOWN))
			.isInstanceOf(AlertDeliveryException.class);
	}

	@Test
	void messagesReadNaturally() {
		assertThat(DOWN.text()).isEqualTo("""
				🔴 API is down
				API is down.
				URL: https://api.example.com/health
				Reason: Expected a 2xx status, got 503.
				Since: 2026-11-11 14:03:00 UTC""");
		assertThat(AlertMessage.humanDuration(Duration.ofSeconds(45))).isEqualTo("45 s");
		assertThat(AlertMessage.humanDuration(Duration.ofMinutes(5))).isEqualTo("5 min");
		assertThat(AlertMessage.humanDuration(Duration.ofHours(2))).isEqualTo("2 h");
		assertThat(AlertMessage.test("Ops").title()).isEqualTo("Pulse test alert");
		AlertMessage noEndTime = new AlertMessage(AlertKind.RECOVERED, "API", "https://x/", null, OPENED, null, "T");
		assertThat(noEndTime.body()).isEqualTo("API is back up.\nURL: https://x/");
	}

}
