package dev.pulse.common;

import org.springframework.http.HttpStatus;

/**
 * An error the API reports to the client on purpose, with a status code and a
 * message safe to show. ApiExceptionHandler turns it into problem+json.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	public ApiException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
