package dev.pulse.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under "pulse.ai". Any OpenAI-compatible Chat Completions API works;
 * the default is Google Gemini.
 *
 * @param apiKey empty means AI summaries are switched off
 */
@ConfigurationProperties("pulse.ai")
public record AiProperties(String baseUrl, String apiKey, String model, Duration timeout) {

	public boolean hasKey() {
		return apiKey != null && !apiKey.isBlank();
	}

	/** Never print the key, even by accident in a log line. */
	@Override
	public String toString() {
		return "AiProperties[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=" + (hasKey() ? "set" : "empty") + "]";
	}

}
