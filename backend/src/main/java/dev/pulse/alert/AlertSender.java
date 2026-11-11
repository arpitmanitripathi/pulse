package dev.pulse.alert;

/** Delivers one message to one kind of channel. */
public interface AlertSender {

	AlertChannelType type();

	void send(String target, AlertMessage message) throws AlertDeliveryException;

}
