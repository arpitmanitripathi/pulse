package dev.pulse.auth;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {

	static final String REFRESH_COOKIE = "refresh_token";
	private static final String COOKIE_PATH = "/api/auth";

	private final AuthService authService;
	private final AuthProperties properties;

	public AuthController(AuthService authService, AuthProperties properties) {
		this.authService = authService;
		this.properties = properties;
	}

	@PostMapping("/register")
	@Operation(summary = "Create an account")
	public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
		User user = authService.register(request.email(), request.password());
		return ResponseEntity.status(HttpStatus.CREATED).body(new UserResponse(user.getId(), user.getEmail()));
	}

	@PostMapping("/login")
	@Operation(summary = "Log in; returns an access token and sets the refresh-token cookie")
	public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
		return withSession(authService.login(request.email(), request.password()));
	}

	@PostMapping("/refresh")
	@Operation(summary = "Swap the refresh-token cookie for a new access token (the cookie is rotated)")
	public ResponseEntity<TokenResponse> refresh(
			@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
		return withSession(authService.refresh(refreshToken));
	}

	@PostMapping("/logout")
	@Operation(summary = "End this session and clear the cookie")
	public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
		authService.logout(refreshToken);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
	}

	private ResponseEntity<TokenResponse> withSession(AuthService.Session session) {
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE,
						cookie(session.refreshToken(), properties.refreshTokenTtl()).toString())
				.body(new TokenResponse(session.accessToken(), "Bearer", properties.accessTokenTtl().toSeconds()));
	}

	/**
	 * httpOnly: page scripts can't read it. SameSite=Strict: other sites can't
	 * make the browser send it. Path=/api/auth: it is only sent to auth endpoints.
	 */
	private ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
				.httpOnly(true)
				.secure(properties.cookieSecure())
				.sameSite("Strict")
				.path(COOKIE_PATH)
				.maxAge(maxAge)
				.build();
	}

	public record RegisterRequest(
			@NotBlank(message = "Email is required.") @Email(message = "Enter a valid email address.")
			@Size(max = 254, message = "Email must be at most 254 characters.") String email,
			@NotBlank(message = "Password is required.")
			@Size(min = 10, max = 72, message = "Password must be 10 to 72 characters.") String password) {

		/** Trim before validation, so " a@b.com " is accepted as "a@b.com". */
		public RegisterRequest {
			email = email == null ? null : email.trim();
		}
	}

	public record LoginRequest(
			@NotBlank(message = "Email is required.") @Size(max = 254) String email,
			@NotBlank(message = "Password is required.") @Size(max = 200) String password) {

		public LoginRequest {
			email = email == null ? null : email.trim();
		}
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	public record UserResponse(UUID id, String email) {
	}

}
