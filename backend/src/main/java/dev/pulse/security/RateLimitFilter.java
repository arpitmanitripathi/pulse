package dev.pulse.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limits requests per client IP on login, register and password reset (password guessing, account spam)
 * and on /api/public/** (anonymous traffic). Refresh and logout are not limited: their
 * token is 256 random bits, so there is nothing to guess, and the app calls refresh on
 * every page load. Other endpoints need a login and are not limited here.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Duration MINUTE = Duration.ofMinutes(1);

	/** Where a password or an email address is tried; they share one allowance per IP. */
	static final Set<String> LIMITED_AUTH_PATHS = Set.of("/api/auth/login", "/api/auth/register",
			"/api/auth/forgot-password", "/api/auth/reset-password");

	private final RateLimiter rateLimiter;
	private final RateLimitProperties properties;

	public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties) {
		this.rateLimiter = rateLimiter;
		this.properties = properties;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getRequestURI();
		String name;
		int limit;
		if (LIMITED_AUTH_PATHS.contains(path)) {
			name = "auth";
			limit = properties.authPerMinute();
		}
		else if (path.startsWith("/api/public/")) {
			name = "public";
			limit = properties.publicPerMinute();
		}
		else {
			chain.doFilter(request, response);
			return;
		}

		RateLimiter.Decision decision = rateLimiter.tryAcquire(name, request.getRemoteAddr(), limit, MINUTE);
		if (decision.allowed()) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(429);
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
		response.setContentType("application/problem+json");
		response.getWriter().write("""
				{"type":"about:blank","title":"Too Many Requests","status":429,\
				"detail":"Too many requests. Try again in %d seconds."}""".formatted(decision.retryAfterSeconds()));
	}

}
