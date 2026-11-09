package dev.pulse.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimiterTest {

	@Test
	void allowsRequestsWhenRedisIsDown() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));
		RateLimiter limiter = new RateLimiter(redis, Clock.systemUTC());

		RateLimiter.Decision decision = limiter.tryAcquire("auth", "1.2.3.4", 10, Duration.ofMinutes(1));

		assertThat(decision.allowed()).isTrue();
	}

	@Test
	void retryAfterCountsDownToTheEndOfTheCurrentWindow() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
		when(redis.opsForValue().increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(11L);
		// 20 seconds into a minute: the window ends in 40 seconds.
		Clock clock = Clock.fixed(Instant.parse("2026-11-10T10:00:20Z"), ZoneOffset.UTC);
		RateLimiter limiter = new RateLimiter(redis, clock);

		RateLimiter.Decision decision = limiter.tryAcquire("auth", "1.2.3.4", 10, Duration.ofMinutes(1));

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.retryAfterSeconds()).isEqualTo(40);
	}

}
