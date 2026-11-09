package dev.pulse.security;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter in Redis: one key per (name, client, window). The first
 * request in a window creates the key with an expiry; each request adds 1.
 * Redis INCR is atomic, so this stays correct with several app instances.
 *
 * If Redis is unreachable the request is allowed (fail open): a rate limiter
 * must not take the whole API down with it.
 */
@Component
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private final StringRedisTemplate redis;
	private final Clock clock;

	public RateLimiter(StringRedisTemplate redis, Clock clock) {
		this.redis = redis;
		this.clock = clock;
	}

	public Decision tryAcquire(String name, String client, int limit, Duration window) {
		long windowSeconds = window.toSeconds();
		long nowSeconds = clock.instant().getEpochSecond();
		long windowIndex = nowSeconds / windowSeconds;
		long retryAfter = (windowIndex + 1) * windowSeconds - nowSeconds;
		String key = "rl:" + name + ":" + client + ":" + windowIndex;
		try {
			Long count = redis.opsForValue().increment(key);
			if (count != null && count == 1) {
				redis.expire(key, window);
			}
			boolean allowed = count == null || count <= limit;
			return new Decision(allowed, retryAfter);
		}
		catch (RuntimeException ex) {
			log.warn("rate_limit.redis_unavailable name={} error={}", name, ex.getClass().getSimpleName());
			return new Decision(true, 0);
		}
	}

	public record Decision(boolean allowed, long retryAfterSeconds) {
	}

}
