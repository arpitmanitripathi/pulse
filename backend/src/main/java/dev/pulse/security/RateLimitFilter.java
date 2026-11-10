package dev.pulse.security;

import java.io.IOException;
import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limits requests per client IP: /api/auth/** (password guessing) and
 * /api/public/** (anonymous traffic). Other endpoints need a login and are not limited here.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Duration MINUTE = Duration.ofMinutes(1);

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
		if (path.startsWith("/api/auth/")) {
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
