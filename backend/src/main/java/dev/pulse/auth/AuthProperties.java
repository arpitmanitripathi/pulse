package dev.pulse.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Settings under "pulse.auth" in application.yml. Validated at startup, so a
 * missing or short JWT secret stops the app instead of running insecurely.
 */
@Validated
@ConfigurationProperties("pulse.auth")
public record AuthProperties(
		@NotNull @Size(min = 32, message = "JWT_SECRET must be at least 32 characters") String jwtSecret,
		@NotNull Duration accessTokenTtl,
		@NotNull Duration refreshTokenTtl,
		boolean cookieSecure,
		@NotBlank(message = "APP_URL (the web app's address, used in password reset links) is required") String appUrl) {
}
