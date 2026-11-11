package dev.pulse.ai;

/** The AI call failed. The message is safe to log: it never contains the API key. */
public class SummaryException extends Exception {

	public SummaryException(String message) {
		super(message);
	}

}
