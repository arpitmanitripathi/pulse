package dev.pulse.security;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the logged-in user's id from a validated access token. */
public final class CurrentUser {

	private CurrentUser() {
	}

	public static UUID id(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
