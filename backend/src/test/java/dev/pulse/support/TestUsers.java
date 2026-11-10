package dev.pulse.support;

import java.util.UUID;

import dev.pulse.auth.AuthService;
import dev.pulse.auth.User;

/**
 * Creates users and access tokens directly through the service, so tests that
 * are not about auth don't spend the auth endpoints' rate limit.
 */
public class TestUsers {

	private final AuthService authService;

	public TestUsers(AuthService authService) {
		this.authService = authService;
	}

	public TestUser create(String email) {
		User user = authService.register(email, "test-password-123");
		String token = authService.login(email, "test-password-123").accessToken();
		return new TestUser(user.getId(), "Bearer " + token);
	}

	/** id plus a ready-to-use Authorization header value. */
	public record TestUser(UUID id, String bearer) {
	}

}
