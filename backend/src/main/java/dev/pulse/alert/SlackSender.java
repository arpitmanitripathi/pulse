package dev.pulse.alert;

import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Slack incoming webhook: POST {"text": "..."}. */
@Component
public class SlackSender implements AlertSender {

	private final WebhookClient webhooks;
	private final JsonMapper json;

	public SlackSender(WebhookClient webhooks, JsonMapper json) {
		this.webhooks = webhooks;
		this.json = json;
	}

	@Override
	public AlertChannelType type() {
		return AlertChannelType.SLACK;
	}

	@Override
	public void send(String target, AlertMessage message) throws AlertDeliveryException {
		webhooks.postJson(target, json.writeValueAsString(Map.of("text", message.text())), "Slack");
	}

}
