package dev.pulse.alert;

import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Discord webhook: POST {"content": "..."}; Discord rejects content over 2000 characters. */
@Component
public class DiscordSender implements AlertSender {

	static final int MAX_CONTENT = 2000;

	private final WebhookClient webhooks;
	private final JsonMapper json;

	public DiscordSender(WebhookClient webhooks, JsonMapper json) {
		this.webhooks = webhooks;
		this.json = json;
	}

	@Override
	public AlertChannelType type() {
		return AlertChannelType.DISCORD;
	}

	@Override
	public void send(String target, AlertMessage message) throws AlertDeliveryException {
		String text = message.text();
		if (text.length() > MAX_CONTENT) {
			text = text.substring(0, MAX_CONTENT - 1) + "…";
		}
		webhooks.postJson(target, json.writeValueAsString(Map.of("content", text)), "Discord");
	}

}
