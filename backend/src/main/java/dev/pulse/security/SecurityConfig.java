package dev.pulse.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import dev.pulse.auth.AuthProperties;
import dev.pulse.auth.JwtService;

/**
 * Who may call what.
 * <ul>
 * <li>Public: register, login, refresh, logout, health, API docs, public status pages.</li>
 * <li>Everything else needs "Authorization: Bearer &lt;access token&gt;".</li>
 * </ul>
 * Stateless: no server-side session, no session cookie. CSRF protection is off
 * because the API authenticates with a header the browser never adds by itself;
 * the one cookie (refresh token) is SameSite=Strict and scoped to /api/auth.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationEntryPoint problemEntryPoint)
			throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
						"/api/auth/logout").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
				.requestMatchers("/actuator/health", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
					.permitAll()
				.requestMatchers("/error").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth
				.jwt(jwt -> { })
				.authenticationEntryPoint(problemEntryPoint))
			.exceptionHandling(e -> e.authenticationEntryPoint(problemEntryPoint));
		return http.build();
	}

	/** 401 responses use the same problem+json shape as every other error. */
	@Bean
	AuthenticationEntryPoint problemEntryPoint() {
		return (request, response, ex) -> {
			response.setStatus(401);
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			response.setContentType("application/problem+json");
			response.getWriter().write("""
					{"type":"about:blank","title":"Unauthorized","status":401,\
					"detail":"Log in to continue. The access token is missing, invalid or expired."}""");
		};
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	SecretKey jwtSigningKey(AuthProperties properties) {
		return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
	}

	/** Checks signature, expiry (60 s clock-skew allowance) and issuer on every request. */
	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtService.ISSUER));
		return decoder;
	}

}
