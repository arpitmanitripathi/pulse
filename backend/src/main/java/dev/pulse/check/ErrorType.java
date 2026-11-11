package dev.pulse.check;

/** Why a check failed. Stored in check_results.error_type. */
public enum ErrorType {
	/** The check succeeded. */
	NONE,
	/** No answer within the monitor's timeout. */
	TIMEOUT,
	/** The hostname does not exist. */
	DNS,
	/** HTTPS problem: bad, expired or mismatched certificate, or failed handshake. */
	TLS,
	/** Connection refused, reset or unreachable. */
	CONNECTION,
	/** The site answered, but with the wrong status code (or too many redirects). */
	STATUS,
	/** Refused by the SSRF guard, for example a redirect to a private address. */
	BLOCKED
}
