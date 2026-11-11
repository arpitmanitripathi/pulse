package dev.pulse.alert;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class DefaultAlertSenders implements AlertSenders {

	private final Map<AlertChannelType, AlertSender> byType = new EnumMap<>(AlertChannelType.class);

	public DefaultAlertSenders(List<AlertSender> senders) {
		senders.forEach(sender -> byType.put(sender.type(), sender));
	}

	@Override
	public void send(AlertChannelType type, String target, AlertMessage message) throws AlertDeliveryException {
		AlertSender sender = byType.get(type);
		if (sender == null) {
			throw new AlertDeliveryException("No sender for " + type + ".");
		}
		sender.send(target, message);
	}

}
