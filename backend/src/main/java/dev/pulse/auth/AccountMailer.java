package dev.pulse.auth;

/**
 * Emails about the account itself (not alerts). An interface so integration tests can record
 * the messages instead of sending them.
 */
public interface AccountMailer {

	/** @param link the one-time reset link; never logged */
	void sendPasswordReset(String email, String link);

}
