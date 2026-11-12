package dev.pulse.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import tools.jackson.databind.json.JsonMapper;

/** The real summarizer against a fake OpenAI-compatible API (the same shape Gemini answers with). */
class OpenAiCompatibleSummarizerTest {

	@RegisterExtension
	static WireMockExtension ai = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	static final String KEY = "test-secret-key-123";
	static final Instant OPENED = Instant.parse("2026-11-11T14:03:00Z");
	static final SummaryInput INPUT = new SummaryInput("API", "https://api.example.com/health", OPENED, List.of(
			new SummaryInput.CheckLine(OPENED, 503, 120, "STATUS", "Expected a 2xx status, got 503."),
			new SummaryInput.CheckLine(OPENED.minusSeconds(60), null, 5000, "TIMEOUT", "No answer within 5000 ms.")));

	OpenAiCompatibleSummarizer summarizer(String baseUrl, String key, Duration timeout) {
		return new OpenAiCompatibleSummarizer(new AiProperties(baseUrl, key, "gemini-test", timeout),
				JsonMapper.builder().build());
	}

	OpenAiCompatibleSummarizer summarizer() {
		return summarizer(ai.baseUrl() + "/v1beta/openai", KEY, Duration.ofSeconds(2));
	}

	static String answer(String content) {
		return """
				{"id":"x","object":"chat.completion","model":"gemini-test",
				 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":%s}}]}"""
			.formatted(content == null ? "null" : "\"" + content + "\"");
	}

	@Test
	void sendsTheChecksAndReturnsTheText() throws Exception {
		ai.stubFor(post("/v1beta/openai/chat/completions").willReturn(aResponse().withStatus(200)
			.withHeader("Content-Type", "application/json")
			.withBody(answer("API has returned 503 since 14:03 after a timeout; the server is likely overloaded."))));

		String summary = summarizer().summarize(INPUT);

		assertThat(summary).isEqualTo("API has returned 503 since 14:03 after a timeout; the server is likely overloaded.");
		ai.verify(postRequestedFor(urlEqualTo("/v1beta/openai/chat/completions"))
			.withHeader("Authorization", equalTo("Bearer " + KEY))
			.withRequestBody(matchingJsonPath("$.model", equalTo("gemini-test")))
			.withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
			.withRequestBody(matchingJsonPath("$.messages[0].content", containing("Use only the check data")))
			.withRequestBody(matchingJsonPath("$.messages[1].content",
					containing("when marked down | 503 | 120 ms | STATUS | Expected a 2xx status, got 503.")))
			.withRequestBody(matchingJsonPath("$.messages[1].content",
					containing("1 min before | no response | 5000 ms | TIMEOUT |"))));
	}

	@Test
	void thePromptHasNoClockTimesForTheModelToRepeat() {
		String prompt = OpenAiCompatibleSummarizer.userPrompt(INPUT);

		assertThat(prompt).doesNotContain("2026").doesNotContain("UTC").doesNotContain("14:03");
		assertThat(OpenAiCompatibleSummarizer.SYSTEM_PROMPT)
			.contains("Do not write any dates, clock times or time zones")
			.contains("Never call a 4xx a server error")
			.contains("HTTP 5xx");
	}

	@Test
	void timesAreRelativeToWhenTheMonitorWasMarkedDown() {
		assertThat(OpenAiCompatibleSummarizer.relative(OPENED, OPENED)).isEqualTo("when marked down");
		assertThat(OpenAiCompatibleSummarizer.relative(OPENED.minusSeconds(45), OPENED)).isEqualTo("45 s before");
		assertThat(OpenAiCompatibleSummarizer.relative(OPENED.minusSeconds(150), OPENED)).isEqualTo("2 min 30 s before");
		assertThat(OpenAiCompatibleSummarizer.relative(OPENED.minusSeconds(600), OPENED)).isEqualTo("10 min before");
		assertThat(OpenAiCompatibleSummarizer.relative(OPENED.plusSeconds(30), OPENED)).isEqualTo("30 s after");
	}

	@Test
	void aTrailingSlashInTheBaseUrlIsFine() throws Exception {
		ai.stubFor(post("/v1beta/openai/chat/completions").willReturn(aResponse().withBody(answer("ok"))));

		assertThat(summarizer(ai.baseUrl() + "/v1beta/openai/", KEY, Duration.ofSeconds(2)).summarize(INPUT))
			.isEqualTo("ok");
	}

	@Test
	void markdownIsRemovedAndLongAnswersAreCutAtAWord() {
		assertThat(OpenAiCompatibleSummarizer.clean("**API** is `down`.\n\n# Cause\n> timeouts"))
			.isEqualTo("API is down. Cause timeouts");
		String cut = OpenAiCompatibleSummarizer.clean("word ".repeat(200));
		assertThat(cut).hasSizeLessThanOrEqualTo(OpenAiCompatibleSummarizer.MAX_SUMMARY).endsWith("word…");
	}

	@Test
	void anErrorStatusIsReportedWithoutTheKey() {
		ai.stubFor(post("/v1beta/openai/chat/completions").willReturn(aResponse().withStatus(429)
			.withBody("{\"error\":\"quota exceeded for key " + KEY + "\"}")));

		assertThatThrownBy(() -> summarizer().summarize(INPUT))
			.isInstanceOf(SummaryException.class)
			.hasMessage("The AI API answered HTTP 429.")
			.message().doesNotContain(KEY);
	}

	@Test
	void aSlowApiTimesOut() {
		ai.stubFor(post("/v1beta/openai/chat/completions")
			.willReturn(aResponse().withBody(answer("late")).withFixedDelay(3000)));

		assertThatThrownBy(() -> summarizer(ai.baseUrl() + "/v1beta/openai", KEY, Duration.ofMillis(500))
			.summarize(INPUT))
			.isInstanceOf(SummaryException.class)
			.hasMessageContaining("could not be reached");
	}

	@Test
	void anUnusableAnswerIsAnError() {
		ai.stubFor(post("/v1beta/openai/chat/completions").willReturn(aResponse().withBody("<html>oops</html>")));
		assertThatThrownBy(() -> summarizer().summarize(INPUT)).hasMessageContaining("not JSON");

		ai.stubFor(post("/v1beta/openai/chat/completions").willReturn(aResponse().withBody(answer(null))));
		assertThatThrownBy(() -> summarizer().summarize(INPUT)).hasMessage("The AI API returned no text.");

		ai.stubFor(post("/v1beta/openai/chat/completions").willReturn(aResponse().withBody("{\"choices\":[]}")));
		assertThatThrownBy(() -> summarizer().summarize(INPUT)).hasMessage("The AI API returned no text.");
	}

	@Test
	void withoutAKeyItIsSwitchedOffAndTheKeyIsNeverPrinted() {
		assertThat(summarizer("https://x", "", Duration.ofSeconds(1)).enabled()).isFalse();
		assertThat(summarizer("https://x", null, Duration.ofSeconds(1)).enabled()).isFalse();
		assertThat(summarizer().enabled()).isTrue();
		assertThat(new AiProperties("https://x", KEY, "m", Duration.ofSeconds(1)).toString())
			.doesNotContain(KEY).contains("apiKey=set");
	}

}
