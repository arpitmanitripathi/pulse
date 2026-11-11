package dev.pulse.alert;

/** The channel did not accept the message. The message is stored as last_error and shown to the user. */
public class AlertDeliveryException extends Exception {

	public AlertDeliveryException(String message) {
		super(message);
	}

}
