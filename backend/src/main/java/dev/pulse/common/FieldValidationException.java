package dev.pulse.common;

import org.springframework.http.HttpStatus;

/**
 * A 400 about one field, found after the basic checks (for example an unsafe
 * URL). Reported in the same "errors" map as annotation-based validation.
 */
public class FieldValidationException extends ApiException {

	private final String field;

	public FieldValidationException(String field, String message) {
		super(HttpStatus.BAD_REQUEST, message);
		this.field = field;
	}

	public String field() {
		return field;
	}

}
