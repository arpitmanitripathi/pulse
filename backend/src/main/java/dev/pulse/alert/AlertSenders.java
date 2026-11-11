package dev.pulse.alert;

/**
 * Picks the sender for a channel type. An interface so integration tests can
 * swap in one that records messages instead of sending them.
 */
public interface AlertSenders {

	void send(AlertChannelType type, String target, AlertMessage message) throws AlertDeliveryException;

}
