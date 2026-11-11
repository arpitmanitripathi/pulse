package dev.pulse.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls an OpenAI-compatible Chat Completions endpoint
 * ({@code POST {baseUrl}/chat/completions}). Gemini, Groq, OpenRouter,
 * GitHub Models and Anthropic all accept this format, so switching provider is
 * a configuration change.
 *
 * <p>The base URL comes from our own configuration, never from users, so this
 * client does not need the SSRF guard.
 */
@Component
public class OpenAiCompatibleSummarizer implements IncidentSummarizer {

	public static final int MAX_SUMMARY = 400;

	static final String SYSTEM_PROMPT = """
			You explain website outages to the owner of the website.
			Use only the check data you are given. Do not guess about their servers, code or hosting.
			Answer in at most two short sentences, under 300 characters, in plain text without markdown.
			Say what is failing and since when, then the most likely kind of cause that the data supports,
			such as server errors, timeouts, DNS, an HTTPS certificate problem or refused connections.""";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
			.withZone(ZoneOffset.UTC);

	private final AiProperties properties;
	private final JsonMapper json;
	private final HttpClient http;

	public OpenAiCompatibleSummarizer(AiProperties properties, JsonMapper json) {
		this.properties = properties;
		this.json = json;
		this.http = HttpClient.newBuilder()
				.connectTimeout(properties.timeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	@Override
	public boolean enabled() {
		return properties.hasKey();
	}

	@Override
	public String summarize(SummaryInput input) throws SummaryException {
		String body = json.writeValueAsString(Map.of(
				"model", properties.model(),
				"temperature", 0.2,
				"messages", List.of(
						Map.of("role", "system", "content", SYSTEM_PROMPT),
						Map.of("role", "user", "content", userPrompt(input)))));
		HttpRequest request = HttpRequest.newBuilder(endpoint())
				.timeout(properties.timeout())
				.header("Authorization", "Bearer " + properties.apiKey())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new SummaryException("The AI API could not be reached: " + ex.getClass().getSimpleName());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SummaryException("Interrupted while waiting for the AI API.");
		}
		if (response.statusCode() < 200 || response.statusCode() > 299) {
			throw new SummaryException("The AI API answered HTTP " + response.statusCode() + ".");
		}
		return clean(contentOf(response.body()));
	}

	static String userPrompt(SummaryInput input) {
		StringBuilder text = new StringBuilder()
				.append("Monitor: ").append(input.monitorName()).append('\n')
				.append("URL: ").append(input.url()).append('\n')
				.append("Marked down at: ").append(TIME.format(input.openedAt())).append('\n')
				.append("Most recent checks, newest first (time | HTTP status | response time | error type | detail):\n");
		for (SummaryInput.CheckLine check : input.checks()) {
			text.append(TIME.format(check.checkedAt())).append(" | ")
				.append(check.statusCode() == null ? "no response" : check.statusCode()).append(" | ")
				.append(check.latencyMs()).append(" ms | ")
				.append(check.errorType()).append(" | ")
				.append(check.errorMessage() == null ? "-" : check.errorMessage()).append('\n');
		}
		return text.toString();
	}

	private String contentOf(String body) throws SummaryException {
		try {
			JsonNode content = json.readTree(body).path("choices").path(0).path("message").path("content");
			if (!content.isString() || content.asString().isBlank()) {
				throw new SummaryException("The AI API returned no text.");
			}
			return content.asString();
		}
		catch (JacksonException ex) {
			throw new SummaryException("The AI API returned something that is not JSON.");
		}
	}

	/** One paragraph of plain text, at most 400 characters, cut at a word. */
	static String clean(String text) {
		String flat = text.replaceAll("[*_`#>]", "").replaceAll("\\s+", " ").trim();
		if (flat.length() <= MAX_SUMMARY) {
			return flat;
		}
		String cut = flat.substring(0, MAX_SUMMARY - 1);
		int space = cut.lastIndexOf(' ');
		return (space > MAX_SUMMARY / 2 ? cut.substring(0, space) : cut) + "…";
	}

	private URI endpoint() {
		String base = properties.baseUrl().endsWith("/")
				? properties.baseUrl().substring(0, properties.baseUrl().length() - 1)
				: properties.baseUrl();
		return URI.create(base + "/chat/completions");
	}

}
